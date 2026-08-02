package io.engage.sdk.push.fcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngagePushPayloadTest {
    @Test
    fun `decodes engage delivery metadata and custom arguments`() {
        val payload = EngagePushPayload.from(
            mapOf(
                "engage_delivery_id" to "delivery-1",
                "engage_message_id" to "message-1",
                "engage_action_type" to "CUSTOM",
                "engage_action_value" to "open_order",
                "engage_action_arg_order_id" to "order-42",
                "merchant" to "Paris",
            ),
        )

        assertEquals("delivery-1", payload?.deliveryId)
        assertEquals("open_order", payload?.actionValue)
        assertEquals(mapOf("order_id" to "order-42"), payload?.actionArguments)
        assertEquals("Paris", payload?.data?.get("merchant"))
    }

    @Test
    fun `ignores notifications not issued by engage`() {
        assertNull(EngagePushPayload.from(mapOf("engage_message_id" to "message-1")))
    }
}

