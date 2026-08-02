package io.engage.sdk.messagecenter

import io.engage.sdk.Inbox
import io.engage.sdk.InboxEntryId
import io.engage.sdk.InboxPager
import io.engage.sdk.InboxPagerState
import io.engage.sdk.spi.EngageModuleContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class DefaultInbox(private val context: EngageModuleContext) : Inbox {
    override val unreadCount: StateFlow<Int> = MutableStateFlow(0)

    override fun pager(pageSize: Int): InboxPager {
        require(pageSize in 1..100) { "Inbox pageSize must be between 1 and 100" }
        return EmptyPager()
    }

    override suspend fun markRead(entryId: InboxEntryId) = Unit
    override suspend fun markUnread(entryId: InboxEntryId) = Unit
    override suspend fun markAllRead() = Unit
    override suspend fun delete(entryId: InboxEntryId) = Unit
}

private class EmptyPager : InboxPager {
    override val state: StateFlow<InboxPagerState> = MutableStateFlow(InboxPagerState())
    override suspend fun refresh() = Unit
    override suspend fun loadNextPage() = Unit
    override fun close() = Unit
}

