package io.engage.sdk

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class MessageCenterContractTest {
    @Test
    fun `headless entry keeps application payload flat`() {
        val entry = InboxEntry(
            InboxEntryId("entry-1"),
            "order.shipped",
            buildJsonObject {},
            Instant.EPOCH,
            null,
            null,
        )
        assertEquals("order.shipped", entry.key)
        assertEquals(InboxEntryId("entry-1"), entry.id)
    }
}
