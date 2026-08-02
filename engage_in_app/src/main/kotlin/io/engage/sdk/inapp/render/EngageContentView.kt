package io.engage.sdk.inapp.render

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Rect
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
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
import com.yandex.div.core.view2.Div2View
import com.yandex.div.data.DivParsingEnvironment
import com.yandex.div.json.ParsingErrorLogger
import com.yandex.div2.DivData
import io.engage.sdk.InAppContent
import io.engage.sdk.InAppContentType
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
    private var visibleReported = false
    private val preDrawListener = android.view.ViewTreeObserver.OnPreDrawListener {
        reportVisibilityIfNeeded()
        true
    }

    init {
        clipChildren = false
        clipToPadding = false
        runCatching { createContentView() }
            .onSuccess {
                val height = if ((content.presentation as? io.engage.sdk.OverlayPresentation)?.format ==
                    io.engage.sdk.OverlayFormat.FULLSCREEN
                ) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
                addView(it, LayoutParams(LayoutParams.MATCH_PARENT, height))
            }
            .onFailure { callbacks.onRenderFailed(content) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
        reportVisibilityIfNeeded()
    }

    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        (getChildAt(0) as? Div2View)?.cleanup()
        (getChildAt(0) as? WebView)?.destroy()
        super.onDetachedFromWindow()
    }

    private fun createContentView(): View = when (content.type) {
        InAppContentType.SCENE,
        InAppContentType.SURVEY,
        -> createDivView()
        InAppContentType.IMAGE -> createImageView()
        InAppContentType.WEB -> createWebView()
    }

    private fun createDivView(): View {
        val json = JSONObject(content.payload.toString())
        val environment = DivParsingEnvironment(ParsingErrorLogger.LOG)
        json.optJSONObject("templates")?.let(environment::parseTemplates)
        val card = json.optJSONObject("card") ?: json
        val data = DivData(environment, card)
        val actionHandler = EngageDivActionHandler(content, callbacks)
        val configuration = DivConfiguration.Builder(CoilDivImageLoader(context))
            .actionHandler(actionHandler)
            .enableAccessibility(true)
            .build()
        val divContext = Div2Context(
            ContextThemeWrapper(context, context.theme),
            configuration,
        )
        return Div2View(divContext, null, 0).apply {
            check(setData(data, DivDataTag(content.messageId))) { "DivKit rejected the scene" }
        }
    }

    private fun createImageView(): View {
        val url = content.payload.string("url") ?: error("Image content requires a url")
        return ImageView(context).apply {
            adjustViewBounds = true
            scaleType = when (content.payload.string("contentMode")) {
                "FIT" -> ImageView.ScaleType.FIT_CENTER
                "CENTER_CROP" -> ImageView.ScaleType.CENTER_CROP
                else -> ImageView.ScaleType.CENTER_CROP
            }
            setOnClickListener { callbacks.onClicked(content) }
            load(url)
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun createWebView(): View {
        val url = content.payload.string("url")
        val html = content.payload.string("html")
        require(url != null || html != null) { "Web content requires url or html" }
        return WebView(context).apply {
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptEnabled = content.payload.boolean("javaScriptEnabled") == true
            webViewClient = object : WebViewClient() {
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
        if (uri.scheme == "engage") {
            EngageDivActionHandler(content, callbacks).handleEngageUri(uri)
            return true
        }
        callbacks.onClicked(content)
        return false
    }

    private fun reportVisibilityIfNeeded() {
        if (visibleReported || !isShown || width <= 0 || height <= 0) return
        val visible = Rect()
        if (!getGlobalVisibleRect(visible)) return
        val visibleArea = visible.width().toLong() * visible.height().toLong()
        val totalArea = width.toLong() * height.toLong()
        if (totalArea > 0 && visibleArea * 2 >= totalArea) {
            visibleReported = true
            callbacks.onVisible(content)
        }
    }
}

private class EngageDivActionHandler(
    private val content: InAppContent,
    private val callbacks: InAppRenderCallbacks,
) : DivActionHandler() {
    override fun handleActionUrl(actionUrl: Uri?, view: DivViewFacade): Boolean {
        actionUrl ?: return false
        return if (actionUrl.scheme == "engage") {
            handleEngageUri(actionUrl)
            true
        } else {
            callbacks.onClicked(content)
            super.handleActionUrl(actionUrl, view)
        }
    }

    fun handleEngageUri(uri: Uri) {
        callbacks.onClicked(content)
        when (uri.host) {
            "dismiss" -> callbacks.onDismissed(content)
            "conversion" -> callbacks.onConversion(content)
            "action" -> {
                val name = uri.pathSegments.firstOrNull() ?: return
                val arguments = uri.getQueryParameter("arguments")?.let { raw ->
                    runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject }.getOrNull()
                } ?: JsonObject(emptyMap())
                callbacks.onAction(content, name, arguments)
            }
        }
    }
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
