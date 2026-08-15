package io.engage.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class PushStatusTest {
    @Test
    fun `delivery eligibility states stay independent`() {
        val status = PushStatus(
            permission = PushPermission.DENIED,
            subscription = PushSubscriptionState.OPTED_IN,
            tokenRegistered = true,
        )

        assertEquals(PushSubscriptionState.OPTED_IN, status.subscription)
        assertEquals(PushPermission.DENIED, status.permission)
    }
}
