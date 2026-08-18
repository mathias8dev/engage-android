package io.engage.sdk.messagecenter.divkit.render

import android.net.Uri
import io.engage.sdk.Inbox
import io.engage.sdk.InboxEntryId
import io.engage.sdk.InboxPager
import io.engage.sdk.InboxPagerState
import io.engage.sdk.InboxRenderingSnapshot
import io.engage.sdk.InboxSortOrder
import io.engage.sdk.MessageCenterRenderingSupport
import io.engage.sdk.MessageCenterPresentationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InboxActionRouterTest {
    @Test
    fun `custom action marks the entry read and forwards decoded arguments`() = runTest {
        val inbox = RecordingInbox()
        val support = RecordingRenderingSupport()
        val router = InboxActionRouter(inbox, support)
        val entryId = InboxEntryId("entry-1")
        val uri = Uri.parse(
            "engage://action/open-order?arguments=%7B%22orderId%22%3A%2242%22%7D",
        )

        assertTrue(router.supports(uri))
        assertTrue(router.handle(uri, entryId))

        assertEquals(listOf("read:entry-1"), inbox.operations)
        assertEquals("open-order", support.actionName)
        assertEquals("\"42\"", support.arguments?.get("orderId").toString())
    }

    @Test
    fun `built-in delete never enters the application action registry`() = runTest {
        val inbox = RecordingInbox()
        val support = RecordingRenderingSupport()
        var invalidated: InboxEntryId? = null
        val router = InboxActionRouter(inbox, support, onDeleted = { invalidated = it })

        assertTrue(router.handle(Uri.parse("engage://delete"), InboxEntryId("entry-2")))

        assertEquals(listOf("delete:entry-2"), inbox.operations)
        assertEquals(InboxEntryId("entry-2"), invalidated)
        assertEquals(null, support.actionName)
    }
}

private class RecordingInbox : Inbox {
    override val unreadCount: StateFlow<Int> = MutableStateFlow(0)
    val operations = mutableListOf<String>()

    override fun pager(pageSize: Int, sortOrder: InboxSortOrder): InboxPager = object : InboxPager {
        override val state: StateFlow<InboxPagerState> = MutableStateFlow(InboxPagerState())
        override suspend fun refresh() = Unit
        override suspend fun loadNextPage() = Unit
        override fun close() = Unit
    }
    override suspend fun markRead(entryId: InboxEntryId) { operations += "read:${entryId.value}" }
    override suspend fun markUnread(entryId: InboxEntryId) { operations += "unread:${entryId.value}" }
    override suspend fun markAllRead() { operations += "read-all" }
    override suspend fun delete(entryId: InboxEntryId) { operations += "delete:${entryId.value}" }
}

private class RecordingRenderingSupport : MessageCenterRenderingSupport {
    var actionName: String? = null
    var arguments: JsonObject? = null
    override val presentationState = MutableStateFlow(
        MessageCenterPresentationState(0, 0, true, emptySet()),
    )

    override suspend fun resolveRenderings(entryIds: List<InboxEntryId>): List<InboxRenderingSnapshot> = emptyList()

    override suspend fun executeAction(name: String, arguments: JsonObject): Boolean {
        actionName = name
        this.arguments = arguments
        return true
    }
}
