package io.engage.sdk.messagecenter.divkit.render

import io.engage.sdk.InboxEntryId
import io.engage.sdk.InboxPagerState
import io.engage.sdk.messagecenter.divkit.domain.RenderingResolution

internal enum class InboxViewFilter {
    ALL,
    UNREAD,
}

internal enum class MessageCenterEmptyKind {
    INBOX,
    UNREAD,
    ERROR,
}

internal data class MessageCenterUiModel(
    val items: List<InboxUiItem>,
    val messageCount: Int,
    val unreadCount: Int,
    val showFilters: Boolean,
    val showProgress: Boolean,
    val emptyKind: MessageCenterEmptyKind?,
    val showErrorBanner: Boolean,
    val shouldLoadMoreForUnreadFilter: Boolean,
)

internal fun messageCenterUiModel(
    state: InboxPagerState,
    filter: InboxViewFilter,
    reportedUnreadCount: Int,
    renderings: Map<InboxEntryId, RenderingResolution>,
    renderingError: Boolean,
): MessageCenterUiModel {
    val loadedUnreadCount = state.entries.count { it.readAt == null }
    val unreadCount = maxOf(reportedUnreadCount, loadedUnreadCount)
    val visibleEntries = when (filter) {
        InboxViewFilter.ALL -> state.entries
        InboxViewFilter.UNREAD -> state.entries.filter { it.readAt == null }
    }
    val items = visibleEntries.map { entry -> InboxUiItem(entry, renderings[entry.id]) }
    val initialLoading = state.entries.isEmpty() && state.isRefreshing
    val unreadPageLoading =
        filter == InboxViewFilter.UNREAD &&
            items.isEmpty() &&
            (state.hasMore || state.isLoadingMore)
    val emptyKind = when {
        initialLoading || unreadPageLoading -> null
        state.entries.isEmpty() && state.error != null -> MessageCenterEmptyKind.ERROR
        state.entries.isEmpty() -> MessageCenterEmptyKind.INBOX
        filter == InboxViewFilter.UNREAD && items.isEmpty() -> MessageCenterEmptyKind.UNREAD
        else -> null
    }
    return MessageCenterUiModel(
        items = items,
        messageCount = state.entries.size,
        unreadCount = unreadCount,
        showFilters = state.entries.isNotEmpty(),
        showProgress = initialLoading || unreadPageLoading,
        emptyKind = emptyKind,
        showErrorBanner = state.entries.isNotEmpty() && (state.error != null || renderingError),
        shouldLoadMoreForUnreadFilter =
            filter == InboxViewFilter.UNREAD &&
                items.isEmpty() &&
                state.hasMore &&
                !state.isLoadingMore,
    )
}
