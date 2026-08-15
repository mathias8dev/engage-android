package io.engage.sdk.inapp.render

import io.engage.sdk.InAppContent
import kotlinx.serialization.json.JsonObject

internal interface InAppRenderCallbacks {
    fun onVisible(content: InAppContent)
    fun onClicked(content: InAppContent)
    fun onDismissed(content: InAppContent)
    fun onConversion(content: InAppContent)
    fun onAction(content: InAppContent, name: String, arguments: JsonObject)
    fun onRenderFailed(content: InAppContent)
}
