package io.engage.sdk.messagecenter.divkit.render

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
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
import com.yandex.div.core.expression.variables.DivVariableController
import com.yandex.div.core.view2.Div2View
import com.yandex.div.data.Variable
import com.yandex.div.data.DivParsingEnvironment
import com.yandex.div.json.ParsingErrorLogger
import com.yandex.div2.DivData
import io.engage.sdk.InboxEntry
import io.engage.sdk.EngageLogger
import io.engage.sdk.InboxEntryId
import io.engage.sdk.InboxRenderingSurface
import io.engage.sdk.messagecenter.divkit.R
import io.engage.sdk.messagecenter.divkit.MessageCenterMaterialTheme
import io.engage.sdk.messagecenter.divkit.MessageCenterViewLayout
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
    private val materialTheme: MessageCenterMaterialTheme = MessageCenterMaterialTheme.defaults(context),
    private val layout: MessageCenterViewLayout = MessageCenterViewLayout(),
    private val surface: InboxRenderingSurface = InboxRenderingSurface.SUMMARY,
    private val showChrome: Boolean = true,
    private val onContentVisible: ((InboxEntryId) -> Unit)? = null,
    private val onRenderError: ((Throwable) -> Unit)? = null,
) : FrameLayout(context) {
    private var divView: Div2View? = null
    private var appearanceVariable: Variable.StringVariable? = null
    private var boundEntryId: InboxEntryId? = null
    private var reportContentVisibility = false
    private var contentRendered = false
    private var visibilityReported = false
    private val preDrawListener = android.view.ViewTreeObserver.OnPreDrawListener {
        reportVisibilityIfNeeded()
        true
    }

    init {
        minimumHeight = dp(72)
        EngageLogger.verbose("MessageCenter.DivKit", "item view created")
    }

    fun bind(item: InboxUiItem) {
        EngageLogger.debug(
            "MessageCenter.DivKit",
            "binding entryId=${item.entry.id} read=${item.entry.readAt != null} " +
                "rendering=${item.rendering?.let { it::class.simpleName } ?: "loading"}",
        )
        bindRendering(
            entryId = item.entry.id,
            rendering = item.rendering,
            unread = item.entry.readAt == null,
            reportVisibility = false,
        )
    }

    fun bindDetail(entryId: InboxEntryId, rendering: RenderingResolution) {
        bindRendering(entryId, rendering, unread = false, reportVisibility = true)
    }

    private fun bindRendering(
        entryId: InboxEntryId,
        rendering: RenderingResolution?,
        unread: Boolean,
        reportVisibility: Boolean,
    ) {
        if (boundEntryId != entryId || reportContentVisibility != reportVisibility) {
            visibilityReported = false
        }
        boundEntryId = entryId
        reportContentVisibility = reportVisibility
        contentRendered = false
        divView?.cleanup()
        divView = null
        removeAllViews()
        applyNativeChrome(
            enabled = shouldApplyMessageCenterNativeChrome(
                showChrome = showChrome,
                hasPublishedRendering = rendering is RenderingResolution.Available,
            ),
            read = !unread,
        )
        when (rendering) {
            null -> addCentered(
                ProgressBar(context).apply {
                    indeterminateTintList = ColorStateList.valueOf(materialTheme.primary)
                },
            )
            is RenderingResolution.Unavailable -> addCentered(
                TextView(context).apply {
                    setText(R.string.engage_message_center_unavailable)
                    setTextColor(materialTheme.onSurfaceVariant)
                    gravity = Gravity.CENTER
                    setPadding(dp(24), dp(24), dp(24), dp(24))
                },
            )
            is RenderingResolution.Available -> runCatching {
                createDivView(entryId, rendering)
            }.onSuccess { view ->
                divView = view
                contentRendered = true
                addView(
                    view,
                    LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        if (surface == InboxRenderingSurface.DETAIL) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT,
                    ),
                )
                EngageLogger.debug(
                    "MessageCenter.DivKit",
                    "rendering bound entryId=$entryId surface=$surface revision=${rendering.snapshot.revision}",
                )
            }.onFailure { error ->
                applyNativeChrome(enabled = showChrome, read = !unread)
                EngageLogger.error("MessageCenter.DivKit", "rendering failed entryId=$entryId surface=$surface", error)
                onRenderError?.invoke(error)
                addCentered(
                    TextView(context).apply {
                        setText(R.string.engage_message_center_unavailable)
                        setTextColor(materialTheme.onSurfaceVariant)
                        gravity = Gravity.CENTER
                        setPadding(dp(24), dp(24), dp(24), dp(24))
                    },
                )
            }
        }
        if (showChrome && unread) addUnreadIndicator()
    }

    fun recycle() {
        EngageLogger.verbose("MessageCenter.DivKit", "item recycled entryId=$boundEntryId surface=$surface")
        boundEntryId = null
        reportContentVisibility = false
        contentRendered = false
        visibilityReported = false
        divView?.cleanup()
        divView = null
        removeAllViews()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        EngageLogger.verbose("MessageCenter.DivKit", "item attached entryId=$boundEntryId surface=$surface")
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    override fun onDetachedFromWindow() {
        EngageLogger.verbose("MessageCenter.DivKit", "item detached entryId=$boundEntryId surface=$surface")
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        appearanceVariable?.set(messageCenterDivKitAppearanceValue(newConfig.uiMode).wireValue)
    }

    private fun createDivView(
        entryId: InboxEntryId,
        rendering: RenderingResolution.Available,
    ): Div2View {
        EngageLogger.debug(
            "MessageCenter.DivKit",
            "DivKit parsing entryId=$entryId surface=$surface revision=${rendering.snapshot.revision}",
        )
        val json = JSONObject(rendering.snapshot.requireSurface(surface).toString())
        val environment = DivParsingEnvironment(ParsingErrorLogger.LOG)
        json.optJSONObject("templates")?.let(environment::parseTemplates)
        val card = json.optJSONObject("card") ?: json
        val data = DivData(environment, card)
        val variable = Variable.StringVariable(
            MESSAGE_CENTER_ENGAGE_APPEARANCE_VARIABLE,
            messageCenterDivKitAppearanceValue(resources.configuration.uiMode).wireValue,
        )
        appearanceVariable = variable
        val variableController = DivVariableController().apply { putOrUpdate(variable) }
        val configuration = DivConfiguration.Builder(CoilDivImageLoader(context))
            .actionHandler(InboxDivActionHandler(entryId, scope, actionRouter))
            .divVariableController(variableController)
            .enableAccessibility(true)
            .build()
        val divContext = Div2Context(ContextThemeWrapper(context, context.theme), configuration)
        return Div2View(divContext, null, 0).apply {
            check(setData(data, DivDataTag("inbox:${entryId.value}:${surface.name}:${rendering.snapshot.revision}"))) {
                "DivKit rejected the Inbox snapshot"
            }
            EngageLogger.debug("MessageCenter.DivKit", "DivKit data accepted entryId=$entryId surface=$surface")
        }
    }

    private fun reportVisibilityIfNeeded() {
        val entryId = boundEntryId ?: return
        if (!shouldReportContentVisibility(surface, reportContentVisibility, contentRendered) || visibilityReported) return
        if (!isShown || width <= 0 || height <= 0) return
        val visible = Rect()
        if (!getGlobalVisibleRect(visible)) return
        val visibleArea = visible.width().toLong() * visible.height().toLong()
        val totalArea = width.toLong() * height.toLong()
        if (totalArea > 0 && visibleArea * 2 >= totalArea) {
            visibilityReported = true
            EngageLogger.info(
                "MessageCenter.DivKit",
                "content visibility threshold reached entryId=$entryId surface=$surface",
            )
            onContentVisible?.invoke(entryId)
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
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(2).toFloat()
                    setColor(materialTheme.primary)
                }
                contentDescription = context.getString(R.string.engage_message_center_unread)
            },
            LayoutParams(dp(4), LayoutParams.MATCH_PARENT, Gravity.START),
        )
        addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(materialTheme.primary)
                }
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LayoutParams(dp(8), dp(8), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(12)
                marginEnd = dp(12)
            },
        )
    }

    private fun applyNativeChrome(enabled: Boolean, read: Boolean) {
        background = if (enabled) cardBackground(read) else null
        clipToOutline = enabled
        elevation = if (enabled) dp(2).toFloat() else 0f
    }

    private fun cardBackground(read: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(layout.itemCornerRadiusDp)
        setColor(if (read) materialTheme.surfaceContainer else materialTheme.surfaceContainerLow)
        setStroke(dp(1), materialTheme.outlineVariant)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

internal const val MESSAGE_CENTER_ENGAGE_APPEARANCE_VARIABLE = "engage_appearance"

internal enum class MessageCenterDivKitAppearanceValue(val wireValue: String) {
    SYSTEM_LIGHT("system_light"),
    SYSTEM_DARK("system_dark"),
}

internal fun messageCenterDivKitAppearanceValue(uiMode: Int): MessageCenterDivKitAppearanceValue =
    if (uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) {
        MessageCenterDivKitAppearanceValue.SYSTEM_DARK
    } else {
        MessageCenterDivKitAppearanceValue.SYSTEM_LIGHT
    }

internal fun shouldReportContentVisibility(
    surface: InboxRenderingSurface,
    requested: Boolean,
    rendered: Boolean,
): Boolean = requested && rendered && surface == InboxRenderingSurface.DETAIL

internal fun shouldApplyMessageCenterNativeChrome(
    showChrome: Boolean,
    hasPublishedRendering: Boolean,
): Boolean = showChrome && !hasPublishedRendering

private class InboxDivActionHandler(
    private val entryId: InboxEntryId,
    private val scope: CoroutineScope,
    private val router: InboxActionRouter,
) : DivActionHandler() {
    override fun handleActionUrl(actionUrl: Uri?, view: DivViewFacade): Boolean {
        actionUrl ?: return false
        EngageLogger.info(
            "MessageCenter.DivKit",
            "DivKit action entryId=$entryId scheme=${actionUrl.scheme} host=${actionUrl.host}",
        )
        if (router.supports(actionUrl)) {
            scope.launch {
                runCatching { router.handle(actionUrl, entryId) }
                    .onFailure { error ->
                        EngageLogger.error("MessageCenter.DivKit", "action failed entryId=$entryId", error)
                    }
            }
            return true
        }
        scope.launch { router.markOpened(entryId) }
        return super.handleActionUrl(actionUrl, view)
    }
}
