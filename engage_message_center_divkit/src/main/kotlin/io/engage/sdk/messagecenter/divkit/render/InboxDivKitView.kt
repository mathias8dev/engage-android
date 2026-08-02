package io.engage.sdk.messagecenter.divkit.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.yandex.div.DivDataTag
import com.yandex.div.coil.CoilDivImageLoader
import com.yandex.div.core.Div2Context
import com.yandex.div.core.DivActionHandler
import com.yandex.div.core.DivConfiguration
import com.yandex.div.core.DivViewFacade
import com.yandex.div.core.view2.Div2View
import com.yandex.div.data.DivParsingEnvironment
import com.yandex.div.json.ParsingErrorLogger
import com.yandex.div2.DivData
import io.engage.sdk.InboxEntry
import io.engage.sdk.messagecenter.divkit.R
import io.engage.sdk.messagecenter.divkit.domain.RenderingResolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class InboxUiItem(
    val entry: InboxEntry,
    val rendering: RenderingResolution?,
)

@SuppressLint("ViewConstructor")
internal class InboxDivKitView(
    context: Context,
    private val scope: CoroutineScope,
    private val actionRouter: InboxActionRouter,
) : FrameLayout(context) {
    private var divView: Div2View? = null
    private var boundItem: InboxUiItem? = null
    private var visibilityReported = false
    private val preDrawListener = android.view.ViewTreeObserver.OnPreDrawListener {
        reportVisibilityIfNeeded()
        true
    }

    init {
        minimumHeight = dp(72)
    }

    fun bind(item: InboxUiItem) {
        if (boundItem?.entry?.id != item.entry.id) visibilityReported = item.entry.readAt != null
        boundItem = item
        divView?.cleanup()
        divView = null
        removeAllViews()
        when (val rendering = item.rendering) {
            null -> addCentered(ProgressBar(context))
            is RenderingResolution.Unavailable -> addCentered(
                TextView(context).apply {
                    setText(R.string.engage_message_center_unavailable)
                    gravity = Gravity.CENTER
                    setPadding(dp(24), dp(24), dp(24), dp(24))
                },
            )
            is RenderingResolution.Available -> runCatching {
                createDivView(item.entry, rendering)
            }.onSuccess { view ->
                divView = view
                addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }.onFailure {
                addCentered(
                    TextView(context).apply {
                        setText(R.string.engage_message_center_unavailable)
                        gravity = Gravity.CENTER
                        setPadding(dp(24), dp(24), dp(24), dp(24))
                    },
                )
            }
        }
        if (item.entry.readAt == null) addUnreadIndicator()
    }

    fun recycle() {
        boundItem = null
        divView?.cleanup()
        divView = null
        removeAllViews()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        super.onDetachedFromWindow()
    }

    private fun createDivView(
        entry: InboxEntry,
        rendering: RenderingResolution.Available,
    ): Div2View {
        val json = JSONObject(rendering.snapshot.document.toString())
        val environment = DivParsingEnvironment(ParsingErrorLogger.LOG)
        json.optJSONObject("templates")?.let(environment::parseTemplates)
        val card = json.optJSONObject("card") ?: json
        val data = DivData(environment, card)
        val configuration = DivConfiguration.Builder(CoilDivImageLoader(context))
            .actionHandler(InboxDivActionHandler(entry, scope, actionRouter))
            .enableAccessibility(true)
            .build()
        val divContext = Div2Context(ContextThemeWrapper(context, context.theme), configuration)
        return Div2View(divContext, null, 0).apply {
            check(setData(data, DivDataTag("inbox:${entry.id.value}:${rendering.snapshot.revision}"))) {
                "DivKit rejected the Inbox snapshot"
            }
        }
    }

    private fun reportVisibilityIfNeeded() {
        val item = boundItem ?: return
        if (visibilityReported || item.entry.readAt != null || item.rendering !is RenderingResolution.Available) return
        if (!isShown || width <= 0 || height <= 0) return
        val visible = Rect()
        if (!getGlobalVisibleRect(visible)) return
        val visibleArea = visible.width().toLong() * visible.height().toLong()
        val totalArea = width.toLong() * height.toLong()
        if (totalArea > 0 && visibleArea * 2 >= totalArea) {
            visibilityReported = true
            scope.launch { actionRouter.markOpened(item.entry.id) }
        }
    }

    private fun addCentered(view: View) {
        addView(
            view,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
    }

    private fun addUnreadIndicator() {
        addView(
            View(context).apply {
                setBackgroundColor(resolveThemeColor(android.R.attr.colorAccent, 0xFF666666.toInt()))
                contentDescription = context.getString(R.string.engage_message_center_unread)
            },
            LayoutParams(dp(4), LayoutParams.MATCH_PARENT, Gravity.START),
        )
    }

    private fun resolveThemeColor(attribute: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) value.data else fallback
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private class InboxDivActionHandler(
    private val entry: InboxEntry,
    private val scope: CoroutineScope,
    private val router: InboxActionRouter,
) : DivActionHandler() {
    override fun handleActionUrl(actionUrl: Uri?, view: DivViewFacade): Boolean {
        actionUrl ?: return false
        if (router.supports(actionUrl)) {
            scope.launch { runCatching { router.handle(actionUrl, entry.id) } }
            return true
        }
        scope.launch { router.markOpened(entry.id) }
        return super.handleActionUrl(actionUrl, view)
    }
}
