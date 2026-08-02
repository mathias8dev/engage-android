# Engage Message Center DivKit for Android

Optional ready-made Android UI for `engage-message-center`. Add this artifact only when the app
wants Engage to render the Inbox snapshots produced by its published DivKit templates.

```kotlin
Engage.messageCenter.display()
```

The Activity reads the same hot, offline-first Inbox as custom integrations. It restores cached
renderings immediately, resolves only missing snapshots in batches, paginates near the end of the
list, supports pull-to-refresh and mark-all-read, and executes custom DivKit actions through
`Engage.actions`.

The headless `InboxEntry.key` and `InboxEntry.payload` remain untouched. Removing this artifact
removes the UI and rendering transport without changing the data-plane API.
