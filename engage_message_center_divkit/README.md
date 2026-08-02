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

An unread entry is marked read after its successfully rendered card is at least 50% visible. The
rendering plane also reserves these action URLs for published DivKit templates:

```text
engage://mark-read
engage://mark-unread
engage://delete
engage://action/<registered-action>?arguments=<url-encoded-json-object>
```

Other URLs retain DivKit's normal handling and mark the entry read when activated. Apps may
override the `engage_message_center_*` string resources to localize the ready-made chrome.

The headless `InboxEntry.key` and `InboxEntry.payload` remain untouched. Removing this artifact
removes the UI and rendering transport without changing the data-plane API.
