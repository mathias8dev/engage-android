package io.engage.sdk.messagecenter.divkit.render

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.engage.sdk.Engage
import io.engage.sdk.EngageLogger
import io.engage.sdk.InboxEntryId
import io.engage.sdk.InboxRenderingSurface
import io.engage.sdk.messageCenter
import io.engage.sdk.MessageCenterPresentationState
import io.engage.sdk.messagecenter.divkit.EngageMessageCenterDivKitModule
import io.engage.sdk.messagecenter.divkit.MessageCenterViewError
import io.engage.sdk.messagecenter.divkit.MessageCenterViewErrorCode
import io.engage.sdk.messagecenter.divkit.R
import io.engage.sdk.messagecenter.divkit.domain.RenderingResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** Reusable Message Center detail content without navigation chrome. */
public class EngageMessageCenterDetailView(
    context: Context,
    public var onUnavailable: (() -> Unit)? = null,
    public var onError: ((MessageCenterViewError) -> Unit)? = null,
) : FrameLayout(context), AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime = EngageMessageCenterDivKitModule.requireRuntime()
    private val inbox = Engage.messageCenter.inbox
    private val renderingSupport = runtime.renderingSupport()
    private val renderer = InboxDivKitView(
        context,
        scope,
        InboxActionRouter(inbox, renderingSupport, onDeleted = ::entryDeleted),
        surface = InboxRenderingSurface.DETAIL,
        showChrome = false,
        onContentVisible = ::markVisible,
        onRenderError = ::renderFailed,
    )
    private val progress = ProgressBar(context)
    private val unavailable = TextView(context).apply {
        setText(R.string.engage_message_center_unavailable)
        setTextColor(ContextCompat.getColor(context, R.color.engage_message_center_text_secondary))
        textSize = 15f
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    private var entryId: InboxEntryId? = null
    private var loadJob: Job? = null
    private var presentationJob: Job? = null
    private var expiryJob: Job? = null
    private var readReported = false
    private var knownByInbox = false
    private var rendered = false
    private var invalidated = false
    private var lifecycleRevision = 0L
    private var closed = false

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.engage_message_center_page))
        renderer.visibility = View.GONE
        addView(renderer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(progress, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(unavailable, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    public fun display(entryId: InboxEntryId) {
        check(!closed) { "EngageMessageCenterDetailView is closed" }
        if (this.entryId == entryId && loadJob?.isActive == true) return
        this.entryId = entryId
        readReported = false
        rendered = false
        invalidated = false
        val presentation = renderingSupport.presentationState.value
        lifecycleRevision = presentation.lifecycleRevision
        knownByInbox = entryId in presentation.entryIds
        renderer.visibility = View.GONE
        unavailable.visibility = View.GONE
        progress.visibility = View.VISIBLE
        loadJob?.cancel()
        expiryJob?.cancel()
        presentationJob?.cancel()
        presentationJob = observePresentation(entryId, lifecycleRevision)
        loadJob = scope.launch {
            try {
                val resolution = runtime.repository.cached(listOf(entryId))[entryId]
                    ?: runtime.repository.resolve(listOf(entryId))[entryId]
                progress.visibility = View.GONE
                if (this@EngageMessageCenterDetailView.entryId != entryId || invalidated) return@launch
                if (resolution is RenderingResolution.Available && !resolution.snapshot.isExpired()) {
                    val current = renderingSupport.presentationState.value
                    if (entryId in current.entryIds) knownByInbox = true
                    if (shouldInvalidateMessageCenterDetail(
                            entryId,
                            lifecycleRevision,
                            current,
                            knownByInbox,
                            rendered,
                        ) || !current.isEnabled
                    ) {
                        showUnavailable(entryId)
                        return@launch
                    }
                    renderer.bindDetail(entryId, resolution)
                    renderer.visibility = View.VISIBLE
                    rendered = true
                    scheduleExpiry(entryId, resolution.snapshot.expiresAt)
                    EngageLogger.info(
                        "MessageCenter.DetailView",
                        "rendered entryId=$entryId revision=${resolution.snapshot.revision}",
                    )
                } else {
                    showUnavailable(entryId)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                progress.visibility = View.GONE
                renderer.visibility = View.GONE
                unavailable.visibility = View.VISIBLE
                EngageLogger.error("MessageCenter.DetailView", "rendering failed entryId=$entryId", error)
                onError?.invoke(
                    MessageCenterViewError(
                        MessageCenterViewErrorCode.RENDERING,
                        error.message ?: "Inbox detail rendering failed",
                        true,
                    ),
                )
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        loadJob?.cancel()
        presentationJob?.cancel()
        expiryJob?.cancel()
        renderer.recycle()
        scope.cancel()
    }

    private fun showUnavailable(entryId: InboxEntryId) {
        if (this.entryId != entryId || invalidated) return
        invalidated = true
        rendered = false
        expiryJob?.cancel()
        renderer.recycle()
        renderer.visibility = View.GONE
        progress.visibility = View.GONE
        unavailable.visibility = View.VISIBLE
        EngageLogger.warn("MessageCenter.DetailView", "rendering unavailable entryId=$entryId")
        onUnavailable?.invoke()
    }

    private fun markVisible(visibleEntryId: InboxEntryId) {
        if (readReported || invalidated || visibleEntryId != entryId) return
        readReported = true
        scope.launch { inbox.markRead(visibleEntryId) }
    }

    private fun observePresentation(entryId: InboxEntryId, expectedLifecycleRevision: Long): Job = scope.launch {
        renderingSupport.presentationState.collectLatest { state ->
            if (this@EngageMessageCenterDetailView.entryId != entryId || invalidated) return@collectLatest
            if (entryId in state.entryIds) knownByInbox = true
            val identityChanged = state.lifecycleRevision != expectedLifecycleRevision
            val removed = knownByInbox && entryId !in state.entryIds
            val disabledAfterRender = rendered && !state.isEnabled
            if (shouldInvalidateMessageCenterDetail(
                    entryId,
                    expectedLifecycleRevision,
                    state,
                    knownByInbox,
                    rendered,
                )
            ) {
                EngageLogger.warn(
                    "MessageCenter.DetailView",
                    "rendering invalidated entryId=$entryId identityChanged=$identityChanged removed=$removed disabled=$disabledAfterRender",
                )
                loadJob?.cancel()
                showUnavailable(entryId)
            }
        }
    }

    private fun scheduleExpiry(entryId: InboxEntryId, expiresAt: Instant?) {
        expiryJob?.cancel()
        expiresAt ?: return
        val delayMillis = Duration.between(Instant.now(), expiresAt).toMillis().coerceAtLeast(0)
        expiryJob = scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            if (this@EngageMessageCenterDetailView.entryId == entryId && !invalidated) {
                EngageLogger.info("MessageCenter.DetailView", "rendering expired entryId=$entryId")
                showUnavailable(entryId)
            }
        }
    }

    private fun entryDeleted(deletedEntryId: InboxEntryId) {
        if (deletedEntryId == entryId && !invalidated) showUnavailable(deletedEntryId)
    }

    private fun io.engage.sdk.InboxRenderingSnapshot.isExpired(): Boolean =
        expiresAt?.let { !it.isAfter(Instant.now()) } == true

    private fun renderFailed(error: Throwable) {
        onError?.invoke(
            MessageCenterViewError(
                MessageCenterViewErrorCode.RENDERING,
                error.message ?: "Inbox detail rendering failed",
                false,
            ),
        )
    }
}

internal fun shouldInvalidateMessageCenterDetail(
    entryId: InboxEntryId,
    expectedLifecycleRevision: Long,
    state: MessageCenterPresentationState,
    knownByInbox: Boolean,
    rendered: Boolean,
): Boolean = state.lifecycleRevision != expectedLifecycleRevision ||
    entryId in state.deletedEntryIds ||
    knownByInbox && entryId !in state.entryIds ||
    rendered && !state.isEnabled
