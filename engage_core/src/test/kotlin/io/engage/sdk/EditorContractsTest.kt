package io.engage.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorContractsTest {
    @Test
    fun `last attribute edit wins atomically`() {
        val changes = AttributeEditor().apply {
            set("plan", "trial")
            remove("plan")
            set("plan", "premium")
        }.build()

        assertEquals("premium", changes.values.getValue("plan").toString().trim('"'))
        assertTrue(changes.removals.isEmpty())
    }

    @Test
    fun `profile subscription keeps one effective choice per list and channel`() {
        val changes = ProfileSubscriptionEditor().apply {
            subscribe("marketing", setOf(Channel.EMAIL, Channel.PUSH))
            unsubscribe("marketing", setOf(Channel.EMAIL))
        }.build()

        assertEquals(
            listOf(
                ProfileSubscriptionChange("marketing", Channel.EMAIL, false),
                ProfileSubscriptionChange("marketing", Channel.PUSH, true),
            ),
            changes,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unstable event names`() {
        validateEventName("Product Viewed")
    }
}

