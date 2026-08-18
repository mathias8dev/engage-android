package io.engage.sdk.messagecenter.divkit.render

import io.engage.sdk.InboxEntry
import io.engage.sdk.InboxEntryId
import io.engage.sdk.InboxError
import io.engage.sdk.InboxErrorCode
import io.engage.sdk.InboxPagerState
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCenterUiModelTest {
    @Test
    fun `empty inbox hides irrelevant controls`() {
        val model = model(InboxPagerState())

        assertEquals(MessageCenterEmptyKind.INBOX, model.emptyKind)
        assertFalse(model.showFilters)
        assertEquals(0, model.messageCount)
        assertFalse(model.showProgress)
    }

    @Test
    fun `unread filter uses readAt from the existing inbox contract`() {
        val state = InboxPagerState(
            entries = listOf(entry("unread"), entry("read", read = true)),
        )

        val model = model(state, filter = InboxViewFilter.UNREAD, unreadCount = 1)

        assertEquals(listOf("unread"), model.items.map { it.entry.id.value })
        assertEquals(2, model.messageCount)
        assertEquals(1, model.unreadCount)
        assertTrue(model.showFilters)
    }

    @Test
    fun `unread filter keeps paging until its first visible result`() {
        val state = InboxPagerState(
            entries = listOf(entry("read", read = true)),
            hasMore = true,
        )

        val model = model(state, filter = InboxViewFilter.UNREAD)

        assertTrue(model.showProgress)
        assertTrue(model.shouldLoadMoreForUnreadFilter)
        assertEquals(null, model.emptyKind)
    }

    @Test
    fun `failed empty inbox presents a retry state`() {
        val state = InboxPagerState(
            error = InboxError(InboxErrorCode.NETWORK, "offline", true),
        )

        assertEquals(MessageCenterEmptyKind.ERROR, model(state).emptyKind)
    }

    private fun model(
        state: InboxPagerState,
        filter: InboxViewFilter = InboxViewFilter.ALL,
        unreadCount: Int = 0,
    ) = messageCenterUiModel(
        state = state,
        filter = filter,
        reportedUnreadCount = unreadCount,
        renderings = emptyMap(),
        renderingError = false,
    )

    private fun entry(id: String, read: Boolean = false) = InboxEntry(
        id = InboxEntryId(id),
        key = id,
        payload = JsonObject(emptyMap()),
        sentAt = Instant.parse("2026-08-05T12:00:00Z"),
        expiresAt = null,
        readAt = Instant.parse("2026-08-05T12:01:00Z").takeIf { read },
    )
}
