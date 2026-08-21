package io.engage.sdk.inapp.render

import android.net.Uri
import io.engage.sdk.EmbeddedPresentation
import io.engage.sdk.EmptyStatePolicy
import io.engage.sdk.InAppContent
import io.engage.sdk.InAppContentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EngageDivActionHandlerTest {
    private val content = InAppContent(
        experienceId = "experience-1",
        messageId = "message-1",
        variantId = null,
        type = InAppContentType.SCENE,
        payload = JsonObject(emptyMap()),
        presentation = EmbeddedPresentation("home.hero", EmptyStatePolicy.COLLAPSE),
    )

    @Test
    fun `routes a DivKit outcome URI with its structured properties`() {
        val callbacks = RecordingCallbacks()
        val handler = EngageDivActionHandler(content, callbacks)

        handler.handleEngageUri(
            Uri.parse("engage://outcome/accepted?properties=%7B%22plan%22%3A%22pro%22%7D"),
        )

        assertSame(content, callbacks.clicked)
        assertSame(content, callbacks.outcomeContent)
        assertEquals("accepted", callbacks.outcomeKey)
        assertEquals(JsonObject(mapOf("plan" to JsonPrimitive("pro"))), callbacks.outcomeProperties)
    }

    @Test
    fun `uses empty properties when a DivKit outcome URI contains malformed JSON`() {
        val callbacks = RecordingCallbacks()
        val handler = EngageDivActionHandler(content, callbacks)

        handler.handleEngageUri(Uri.parse("engage://outcome/declined?properties=not-json"))

        assertEquals("declined", callbacks.outcomeKey)
        assertEquals(JsonObject(emptyMap()), callbacks.outcomeProperties)
    }

    private class RecordingCallbacks : InAppRenderCallbacks {
        var clicked: InAppContent? = null
        var outcomeContent: InAppContent? = null
        var outcomeKey: String? = null
        var outcomeProperties: JsonObject? = null

        override fun onVisible(content: InAppContent) = Unit

        override fun onClicked(content: InAppContent) {
            clicked = content
        }

        override fun onDismissed(content: InAppContent) = Unit

        override fun onConversion(content: InAppContent) = Unit

        override fun onOutcome(content: InAppContent, key: String, properties: JsonObject) {
            outcomeContent = content
            outcomeKey = key
            outcomeProperties = properties
        }

        override fun onAction(content: InAppContent, name: String, arguments: JsonObject) = Unit

        override fun onRenderFailed(content: InAppContent) = Unit
    }
}
