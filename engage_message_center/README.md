# Engage Message Center for Android

Headless, offline-first Inbox support for `engage-core`. The artifact contains no presentation UI
and never interprets the application-defined `InboxEntry.key` or `InboxEntry.payload`.

```kotlin
val pager = Engage.messageCenter.inbox.pager(pageSize = 20)

pager.state.collect { state ->
    renderInbox(
        entries = state.entries,
        refreshing = state.isRefreshing,
        loadingMore = state.isLoadingMore,
        hasMore = state.hasMore,
        error = state.error,
    )
}
```

An entry is a flat application contract. No universal title, body, image, action, or rendering field
is added:

```kotlin
when (entry.key) {
    "order.shipped" -> renderOrderShipped(entry.payload.decode<OrderShippedPayload>())
    else -> renderUnsupported(entry.key)
}
```

Pagination commands are suspend functions and remain idempotent while a request is in flight:

```kotlin
pager.refresh()
pager.loadNextPage()
pager.close()
```

Pagers are hot, shared and non-terminal. Multiple collectors never cause additional requests, and
identical cursor requests across separate pagers are deduplicated. Every pager and `unreadCount`
projects the same generation-scoped SQLite store.

Mutations are persisted before returning, projected optimistically, and flushed from a stable batch
outbox. A permanent rejection rolls the local projection back without terminating any flow:

```kotlin
Engage.messageCenter.inbox.markRead(entry.id)
Engage.messageCenter.inbox.markUnread(entry.id)
Engage.messageCenter.inbox.markAllRead()
Engage.messageCenter.inbox.delete(entry.id)
```

Foreground and network reconnection trigger catch-up. A binding generation change physically drops
the old recipient scope before exposing the new one, and `optOutAndWipe()` erases the entire Inbox
database. Install `engage-message-center-divkit` only when the ready-made Engage UI is wanted.
