package io.engage.sdk.inapp.render

import android.content.Context
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import coil3.load
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
import io.engage.sdk.InAppContent
import io.engage.sdk.InAppContentType
import io.engage.sdk.EngageLogger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject

@SuppressLint("ViewConstructor")
internal class EngageContentView(
    context: Context,
    private val content: InAppContent,
    private val callbacks: InAppRenderCallbacks,
) : FrameLayout(context) {
    private var appearanceVariable: Variable.StringVariable? = null
    private val visibilityGate = RenderedVisibilityGate()
    private val preDrawListener = android.view.ViewTreeObserver.OnPreDrawListener {
        reportVisibilityIfNeeded()
        true
    }

    init {
        EngageLogger.debug(
            "InApp.Render",
            "content view creating messageId=${content.messageId} variant=${content.variantId} type=${content.type}",
        )
        clipChildren = false
        clipToPadding = false
        runCatching { createContentView() }
            .onSuccess {
                val height = if ((content.presentation as? io.engage.sdk.OverlayPresentation)?.format ==
                    io.engage.sdk.OverlayFormat.FULLSCREEN
                ) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
                addView(it, LayoutParams(LayoutParams.MATCH_PARENT, height))
                EngageLogger.debug(
                    "InApp.Render",
                    "content view created messageId=${content.messageId} child=${it.javaClass.simpleName}",
                )
                if (content.type == InAppContentType.SCENE || content.type == InAppContentType.SURVEY) {
                    markContentReady()
                }
            }
            .onFailure { error ->
                EngageLogger.error("InApp.Render", "content view creation failed messageId=${content.messageId}", error)
                callbacks.onRenderFailed(content)
            }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        EngageLogger.verbose("InApp.Render", "content attached messageId=${content.messageId}")
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
        reportVisibilityIfNeeded()
    }

    override fun onDetachedFromWindow() {
        EngageLogger.verbose("InApp.Render", "content detached messageId=${content.messageId}")
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        (getChildAt(0) as? Div2View)?.cleanup()
        (getChildAt(0) as? WebView)?.destroy()
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        appearanceVariable?.set(divKitAppearanceValue(newConfig.uiMode).wireValue)
    }

    private fun createContentView(): View = when (content.type) {
        InAppContentType.SCENE,
        InAppContentType.SURVEY,
        -> createDivView()
        InAppContentType.IMAGE -> createImageView()
        InAppContentType.WEB -> createWebView()
    }

    private fun createDivView(): View {
        EngageLogger.debug("InApp.Render", "DivKit scene parsing messageId=${content.messageId}")
        val json = JSONObject(content.payload.toString())
        val environment = DivParsingEnvironment(ParsingErrorLogger.LOG)
        json.optJSONObject("templates")?.let(environment::parseTemplates)
        val card = json.optJSONObject("card") ?: json
        val data = DivData(environment, card)
        val actionHandler = EngageDivActionHandler(content, callbacks)
        val variable = Variable.StringVariable(
            ENGAGE_APPEARANCE_VARIABLE,
            divKitAppearanceValue(resources.configuration.uiMode).wireValue,
        )
        appearanceVariable = variable
        val variableController = DivVariableController().apply { putOrUpdate(variable) }
        val configuration = DivConfiguration.Builder(CoilDivImageLoader(context))
            .actionHandler(actionHandler)
            .divVariableController(variableController)
            .enableAccessibility(true)
            .build()
        val divContext = Div2Context(
            ContextThemeWrapper(context, context.theme),
            configuration,
        )
        return Div2View(divContext, null, 0).apply {
            check(setData(data, DivDataTag(content.messageId))) { "DivKit rejected the scene" }
            EngageLogger.debug("InApp.Render", "DivKit scene bound messageId=${content.messageId}")
        }
    }

    private fun createImageView(): View {
        val url = content.payload.string("url") ?: error("Image content requires a url")
        EngageLogger.debug("InApp.Render", "image loading messageId=${content.messageId} host=${Uri.parse(url).host}")
        return ImageView(context).apply {
            adjustViewBounds = true
            scaleType = when (content.payload.string("contentMode")) {
                "FIT" -> ImageView.ScaleType.FIT_CENTER
                "CENTER_CROP" -> ImageView.ScaleType.CENTER_CROP
                else -> ImageView.ScaleType.CENTER_CROP
            }
            setOnClickListener { callbacks.onClicked(content) }
            load(url) {
                listener(
                    onSuccess = { _, _ ->
                        EngageLogger.debug("InApp.Render", "image loaded messageId=${content.messageId}")
                        markContentReady()
                    },
                    onError = { _, result ->
                        EngageLogger.error(
                            "InApp.Render",
                            "image load failed messageId=${content.messageId}",
                            result.throwable,
                        )
                        callbacks.onRenderFailed(content)
                    },
                )
            }
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun createWebView(): View {
        val url = content.payload.string("url")
        val html = content.payload.string("html")
        require(url != null || html != null) { "Web content requires url or html" }
        EngageLogger.debug(
            "InApp.Render",
            "web content loading messageId=${content.messageId} source=${if (url != null) "url" else "html"} " +
                "host=${url?.let { Uri.parse(it).host }}",
        )
        return WebView(context).apply {
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptEnabled = content.payload.boolean("javaScriptEnabled") == true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    EngageLogger.debug("InApp.Render", "web content loaded messageId=${content.messageId}")
                    markContentReady()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        EngageLogger.warn(
                            "InApp.Render",
                            "web content load failed messageId=${content.messageId} code=${error.errorCode}",
                        )
                        visibilityGate.markFailed()
                        callbacks.onRenderFailed(content)
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    handleWebUri(request.url)

                @Deprecated("Kept for Android 6 WebView callbacks")
                override fun shouldOverrideUrlLoading(view: WebView, value: String): Boolean =
                    handleWebUri(Uri.parse(value))
            }
            if (url != null) loadUrl(url) else loadDataWithBaseURL(
                content.payload.string("baseUrl"),
                requireNotNull(html),
                "text/html",
                "UTF-8",
                null,
            )
        }
    }

    private fun handleWebUri(uri: Uri): Boolean {
        EngageLogger.debug(
            "InApp.Render",
            "web navigation messageId=${content.messageId} scheme=${uri.scheme} host=${uri.host}",
        )
        if (uri.scheme == "engage") {
            EngageDivActionHandler(content, callbacks).handleEngageUri(uri)
            return true
        }
        callbacks.onClicked(content)
        return false
    }

    private fun reportVisibilityIfNeeded() {
        if (!isShown || width <= 0 || height <= 0) return
        val visible = Rect()
        if (!getGlobalVisibleRect(visible)) return
        val visibleArea = visible.width().toLong() * visible.height().toLong()
        val totalArea = width.toLong() * height.toLong()
        if (visibilityGate.shouldReport(totalArea > 0 && visibleArea * 2 >= totalArea)) {
            EngageLogger.info("InApp.Render", "content visible messageId=${content.messageId}")
            callbacks.onVisible(content)
        }
    }

    private fun markContentReady() {
        EngageLogger.verbose("InApp.Render", "content ready messageId=${content.messageId}")
        visibilityGate.markReady()
        reportVisibilityIfNeeded()
    }
}

internal const val ENGAGE_APPEARANCE_VARIABLE = "engage_appearance"

internal enum class EngageDivKitAppearanceValue(val wireValue: String) {
    SYSTEM_LIGHT("system_light"),
    SYSTEM_DARK("system_dark"),
}

internal fun divKitAppearanceValue(uiMode: Int): EngageDivKitAppearanceValue =
    if (uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) {
        EngageDivKitAppearanceValue.SYSTEM_DARK
    } else {
        EngageDivKitAppearanceValue.SYSTEM_LIGHT
    }

internal class RenderedVisibilityGate {
    private var ready = false
    private var failed = false
    private var reported = false

    fun markReady() {
        if (failed) return
        ready = true
    }

    fun markFailed() {
        failed = true
        ready = false
    }

    fun shouldReport(isVisible: Boolean): Boolean {
        if (!ready || reported || !isVisible) return false
        reported = true
        return true
    }
}

private class EngageDivActionHandler(
    private val content: InAppContent,
    private val callbacks: InAppRenderCallbacks,
) : DivActionHandler() {
    override fun handleActionUrl(actionUrl: Uri?, view: DivViewFacade): Boolean {
        actionUrl ?: return false
        EngageLogger.debug(
            "InApp.Render",
            "DivKit action messageId=${content.messageId} scheme=${actionUrl.scheme} host=${actionUrl.host}",
        )
        return if (actionUrl.scheme == "engage") {
            handleEngageUri(actionUrl)
            true
        } else {
            callbacks.onClicked(content)
            super.handleActionUrl(actionUrl, view)
        }
    }

    fun handleEngageUri(uri: Uri) {
        EngageLogger.info("InApp.Render", "Engage action messageId=${content.messageId} type=${uri.host}")
        callbacks.onClicked(content)
        when (uri.host) {
            "dismiss" -> callbacks.onDismissed(content)
            "conversion" -> callbacks.onConversion(content)
            "action" -> {
                val name = uri.pathSegments.firstOrNull() ?: return
                val arguments = uri.getQueryParameter("arguments")?.let { raw ->
                    runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject }.getOrNull()
                } ?: JsonObject(emptyMap())
                EngageLogger.debug(
                    "InApp.Render",
                    "named action messageId=${content.messageId} name=$name argumentKeys=${arguments.keys.sorted()}",
                )
                callbacks.onAction(content, name, arguments)
            }
        }
    }
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
