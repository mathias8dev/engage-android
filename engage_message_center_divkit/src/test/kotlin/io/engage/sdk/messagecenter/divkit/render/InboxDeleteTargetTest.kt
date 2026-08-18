package io.engage.sdk.messagecenter.divkit.render

import io.engage.sdk.InboxEntry
import io.engage.sdk.InboxEntryId
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InboxDeleteTargetTest {
    @Test
    fun `swipe resolves the entry bound to the view holder`() {
        val entry = InboxEntry(
            id = InboxEntryId("entry-1"),
            key = "order.shipped",
            payload = JsonObject(emptyMap()),
            sentAt = Instant.parse("2026-08-18T10:00:00Z"),
            expiresAt = null,
            readAt = null,
        )

        assertEquals(entry, inboxDeleteTarget(InboxUiItem(entry, null)))
    }

    @Test
    fun `recycled holder cannot target a different entry`() {
        assertNull(inboxDeleteTarget(null))
    }
}
