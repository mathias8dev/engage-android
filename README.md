# Engage Android SDK

The official native Android SDK for Engage. The repository is a Gradle multi-project build with
independently consumable artifacts and one atomic release version.

Current release: `2.2.0`.

## Requirements

- Android API 23 or newer
- `compileSdk` 36
- Java 17 bytecode
- An Engage application key beginning with `eng_app_`
- A Firebase Android application and `google-services.json` when using push notifications

The SDK exposes `java.time` types. Enable core-library desugaring in the consuming application:

```kotlin
android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
}
```

## Modules

| Module | Purpose | Required Engage dependency |
| --- | --- | --- |
| `engage_core` | Installation, profile, events, flags, preferences, persistence and synchronization | None |
| `engage_push_fcm` | FCM registration, notification processing and delivery events | `engage_core` |
| `engage_in_app` | In-app automation and DivKit rendering | `engage_core` |
| `engage_message_center` | Headless Inbox state, synchronization and mutations | `engage_core` |
| `engage_message_center_divkit` | Android Inbox UI and DivKit message rendering | `engage_message_center` |

## Installation

Add JitPack to dependency resolution:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.mathias8dev.engage-android") }
        }
    }
}
```

Repository declarations from libraries are not propagated to consuming applications. This also
applies when the SDK is pulled transitively through `engage_flutter`: pub.dev resolves the Flutter
package, but the host Android build still needs JitPack to resolve the native Engage modules.

Then select only the modules required by the application:

```kotlin
dependencies {
    implementation("com.github.mathias8dev.engage-android:engage-android-core:2.2.0")
    implementation("com.github.mathias8dev.engage-android:engage-android-push-fcm:2.2.0")
    implementation("com.github.mathias8dev.engage-android:engage-android-in-app:2.2.0")
    implementation("com.github.mathias8dev.engage-android:engage-android-message-center:2.2.0")
    implementation("com.github.mathias8dev.engage-android:engage-android-message-center-divkit:2.2.0")
}
```

All modules use the same version. Depending on Core alone does not pull DivKit.

### Local composite build

An application can test changes from this monorepo before publishing a release by substituting the
published coordinates with the local Gradle projects in its `settings.gradle.kts`:

```kotlin
val engageAndroid = file("../engage_sdks/android/engage_android").canonicalFile

includeBuild(engageAndroid) {
    dependencySubstitution {
        val group = "com.github.mathias8dev.engage-android"
        substitute(module("$group:engage-android-core")).using(project(":engage_core"))
        substitute(module("$group:engage-android-push-fcm")).using(project(":engage_push_fcm"))
        substitute(module("$group:engage-android-in-app")).using(project(":engage_in_app"))
        substitute(module("$group:engage-android-message-center"))
            .using(project(":engage_message_center"))
        substitute(module("$group:engage-android-message-center-divkit"))
            .using(project(":engage_message_center_divkit"))
    }
}
```

The configured path must be the monorepo root containing this repository's
`settings.gradle.kts`, not one of the individual module directories. The dependency coordinates
stay unchanged, so removing the composite build returns the application to the published JitPack
artifacts.

## Start the SDK

Start Engage once from `Application.onCreate`, before using any API:

```kotlin
import android.app.Application
import io.engage.sdk.Engage
import io.engage.sdk.EngageConfig
import io.engage.sdk.EngageLogLevel

class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        Engage.start(
            context = this,
            config = EngageConfig(
                appKey = BuildConfig.ENGAGE_APP_KEY,
                logLevel = if (BuildConfig.DEBUG) {
                    EngageLogLevel.DEBUG
                } else {
                    EngageLogLevel.INFO
                },
            ),
        )
    }
}
```

Declare the application class in the host manifest:

```xml
<application
    android:name=".ExampleApplication"
    ... />
```

Calling `Engage.start` again with the same configuration is safe. Starting it with another app key
or endpoint in the same process is rejected so that persisted state cannot cross application
identities. Optional modules register themselves through manifest-merged `ContentProvider`s; the
host does not manually instantiate them.

Observe `Engage.state` when the application needs to expose SDK initialization diagnostics.
Development logs use the `Engage` Logcat tag.

When the same release both upgrades from endpoint-scoped SDK storage and changes the API endpoint,
declare the previous endpoint so Engage can move the correct App Key's durable state:

```kotlin
import java.net.URI

EngageConfig(
    appKey = BuildConfig.ENGAGE_APP_KEY,
    endpoint = URI.create(BuildConfig.ENGAGE_ENDPOINT),
    legacyEndpoints = listOf(URI.create(BuildConfig.PREVIOUS_ENGAGE_ENDPOINT)),
)
```

This one-time migration option is unnecessary when the endpoint is unchanged. It is explicit so a
process configured with several Engage App Keys never guesses which legacy storage it owns.

## Push notifications

Add `engage-android-push-fcm`, configure Firebase in the host application, and apply the Google
Services Gradle plugin as for any FCM application. Engage owns the `FirebaseMessagingService`,
notification workers, open activity, action receiver, and dismiss receiver through manifest
merging.

### Notification appearance

Define the notification channels and categories when starting Engage:

```kotlin
import io.engage.sdk.AndroidPushAction
import io.engage.sdk.AndroidPushCategory
import io.engage.sdk.AndroidPushChannel
import io.engage.sdk.AndroidPushConfig
import io.engage.sdk.Engage
import io.engage.sdk.EngageConfig
import io.engage.sdk.ForegroundPresentation
import io.engage.sdk.NotificationImportance
import io.engage.sdk.PushConfig

Engage.start(
    this,
    EngageConfig(
        appKey = BuildConfig.ENGAGE_APP_KEY,
        push = PushConfig(
            foregroundPresentation = ForegroundPresentation.SHOW,
            android = AndroidPushConfig(
                smallIcon = R.drawable.ic_stat_notification,
                accentColor = R.color.notification_accent,
                defaultChannelKey = "general",
                channels = listOf(
                    AndroidPushChannel(
                        key = "general",
                        name = R.string.notification_channel_general,
                        description = R.string.notification_channel_general_description,
                        importance = NotificationImportance.HIGH,
                    ),
                ),
                categories = listOf(
                    AndroidPushCategory(
                        key = "support",
                        actions = listOf(
                            AndroidPushAction(
                                key = "reply",
                                title = R.string.notification_action_reply,
                                opensApp = true,
                            ),
                        ),
                    ),
                ),
            ),
        ),
    ),
)
```

`ForegroundPresentation.SHOW` lets Engage post notifications while the app is visible;
`SILENT` still processes the delivery without presenting it.

### Permission and subscription

The push artifact declares `POST_NOTIFICATIONS`, but Engage never displays the Android runtime
permission prompt. The host owns its timing and explanation UI:

```kotlin
private val notificationPermission = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
) { granted ->
    if (granted) {
        lifecycleScope.launch { Engage.push.optIn() }
    }
}

fun enablePush() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        lifecycleScope.launch { Engage.push.optIn() }
    }
}
```

`optIn` and `optOut` control the Engage push subscription. Permission, subscription, and token
registration remain distinct and are observable together:

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        Engage.push.status.collect { status ->
            renderPushStatus(status)
        }
    }
}
```

Observe deliveries, opens, dismissals, and action selections through `Engage.push.events`:

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        Engage.push.events.collect { event ->
            when (event) {
                is PushEvent.Opened -> openDestination(event.deepLink)
                is PushEvent.ActionSelected -> handleAction(event.actionKey, event.data)
                else -> Unit
            }
        }
    }
}
```

The standard open flow is automatic. A custom launch activity or framework bridge that receives a
new Engage intent can forward it from `onNewIntent`:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    Engage.push.handleOpenIntent(intent)
}
```

When FCM starts a terminated application process, Android creates the host `Application` before
dispatching to the Engage service. Keeping `Engage.start` in `Application.onCreate` therefore lets
the SDK process cold-start deliveries without a permanently running process.

## Installation and profile data

An Engage installation represents one installed application instance. A profile represents the
person to which one or more installations can be bound. Do not model a person by overwriting the
installation identifier.

Observe the server-issued installation ID:

```kotlin
lifecycleScope.launch {
    Engage.installation.id.collect { installationId ->
        // Expose for diagnostics or support tooling.
    }
}
```

Edit installation-scoped or profile-scoped attributes with typed values:

```kotlin
Engage.installation.editAttributes {
    set("app.theme", "dark")
    set("notifications.transactional", true)
}

Engage.profile.editAttributes {
    set("first_name", "Ada")
    set("loyalty.tier", "gold")
    remove("legacy_status")
}

Engage.profile.editTags {
    add("beta_tester")
    remove("onboarding_pending")
}
```

Attribute edits are durable operations: they are queued locally and synchronized when network
connectivity is available. `issueBindingCode()` supports the server-side identity-binding flow
without putting private backend credentials in the application:

```kotlin
val bindingCode = Engage.installation.issueBindingCode()
sendBindingCodeToYourAuthenticatedBackend(bindingCode)
```

Subscription lists can be scoped either to the installation or to the profile and channel:

```kotlin
Engage.installation.editSubscriptions {
    subscribe("product_updates")
}

Engage.profile.editSubscriptions {
    subscribe("weekly_digest", setOf(Channel.EMAIL, Channel.PUSH))
    unsubscribe("promotions", setOf(Channel.SMS))
}
```

## Events and screens

Events feed analytics, audience evaluation, automation triggers, and experimentation:

```kotlin
Engage.events.track("order_completed") {
    value = 149.90
    transactionId = order.id
    put("currency", "EUR")
    put("item_count", order.items.size)
}

Engage.events.trackScreen("checkout.payment")
```

Call `clearScreen()` when no logical screen is active. Normal events use the durable operation
queue; call `flush()` only when the application has a concrete reason to await pending network
work.

## In-app experiences

Add `engage-android-in-app` to enable overlay and embedded experiences. Engage downloads authored
DivKit documents over HTTPS, caches them, evaluates their triggers locally, and renders them without
compiling UI code into the host application. There is no application-owned WebSocket or SSE
connection to maintain.

### Overlay experiences

Overlays include banners, modals, and fullscreen scenes. The optional display delegate can allow,
defer, or permanently discard a candidate:

```kotlin
Engage.inApp.overlays.displayDelegate = InAppOverlayDisplayDelegate { candidate ->
    if (isCheckoutCriticalStepVisible()) {
        DisplayDecision.DEFER
    } else {
        DisplayDecision.ALLOW
    }
}
```

Use `Engage.inApp.overlays.pause()` and `resume()` for temporary global suppression, such as during
a sensitive authentication flow.

### Embedded placements

Compose applications can render a placement directly:

```kotlin
EngageInAppPlacement(
    key = "home.hero",
    modifier = Modifier.fillMaxWidth(),
)
```

View-system applications can use `EngageInAppView`:

```xml
<io.engage.sdk.EngageInAppView
    android:id="@+id/home_hero"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

```kotlin
findViewById<EngageInAppView>(R.id.home_hero).placementKey = "home.hero"
```

The backend remains responsible for audience, schedule, frequency, priority, and variant selection;
the SDK remains responsible for cached evaluation and native DivKit presentation.

## Custom actions

In-app experiences, Message Center documents, and other Engage content can invoke application-owned
actions. Register handlers early in the process and close the returned registration when its owner
is destroyed:

```kotlin
val registration = Engage.actions.register("open_product") { action ->
    val productId = action.arguments.requireString("product_id")
    productNavigator.open(productId)
    ActionResult.COMPLETED
}

// Later, when the handler owner is destroyed:
registration.close()
```

Return `REJECTED` when the host recognizes the action but cannot safely execute it.

## Preference Center

The Core module includes a ready-to-use Preference Center activity:

```kotlin
Engage.preferenceCenter.display()
// Or open a specific center:
Engage.preferenceCenter.display(PreferenceCenterDisplayOptions(key = "marketing"))
```

For custom UI, observe `center()` or `center(key)` and render the returned sections and subscription
choices. Mutations continue through the installation and profile subscription APIs.

## Message Center

Add `engage-android-message-center` for the headless inbox. Add
`engage-android-message-center-divkit` for Engage's ready-to-use Inbox activity and DivKit renderer:

```kotlin
Engage.messageCenter.display()
Engage.messageCenter.display(entryId = entry.id)
```

Each published locale contains a compact `SUMMARY` surface and a full `DETAIL` surface. The ready-made
activity renders `SUMMARY` in the inbox, then opens a native detail screen and renders `DETAIL` when
the row is selected. The template owns the message content; the SDK owns the navigation chrome and
marks the entry read only after `DETAIL` is actually visible. Both surfaces are immutable snapshots of the same headless payload and template
revision.

Applications that own their navigation can embed the same native content without launching an
Activity:

```kotlin
val list = EngageMessageCenterListView(
    context,
    sortOrder = InboxSortOrder.NEWEST_FIRST,
    onEntryTap = { entry -> router.openMessage(entry.id) },
)

val detail = EngageMessageCenterDetailView(
    context,
    onUnavailable = router::closeMissingMessage,
).apply {
    display(entry.id)
}
```

Both views must be closed with their host lifecycle. They contain no toolbar or navigation and share
the same Inbox store, rendering cache, DivKit runtime, and action router as `display()`.
The list header presents the synchronized message and unread counts above a compact All/Unread
segmented filter; bulk read mutations remain available through the headless Inbox API.
The ready-made list also owns the standard destructive interaction: swipe a message toward the
start edge, then confirm deletion in a native Material 3 dialog. The dialog consumes the same
`MessageCenterMaterialTheme` roles as the list (`surfaceContainer`, `onSurface`, `onSurfaceVariant`,
`primary`, and `error`); it never falls back to a technical Activity theme. Only the explicit destructive
action enqueues the durable Inbox mutation and removes the entry optimistically from every active
subscriber. Custom inboxes keep the same mutation available through `Inbox.delete`.

The headless API supports custom Compose or View-system inboxes:

```kotlin
private val inboxPager by lazy {
    Engage.messageCenter.inbox.pager(
        pageSize = 20,
        sortOrder = InboxSortOrder.NEWEST_FIRST,
    )
}

override fun onStart() {
    super.onStart()
    lifecycleScope.launch { inboxPager.refresh() }
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            inboxPager.state.collect(::renderInbox)
        }
    }
}

override fun onDestroy() {
    inboxPager.close()
    super.onDestroy()
}
```

Use `unreadCount`, `markRead`, `markUnread`, `markAllRead`, and `delete` for host-owned badges and
interactions. Sorting is server-side on `sentAt`; each sort order owns a separate cursor window.

## Feature flags

Feature flag reads are synchronous and use the latest locally cached snapshot:

```kotlin
val redesignedCheckout = Engage.flags.getBoolean(
    key = "checkout.redesign",
    default = false,
)

val searchLimit = Engage.flags.getNumber(
    key = "search.result_limit",
    default = 20.0,
)
```

Always provide a safe default. Exposure and analytics behavior is handled by the SDK rather than by
the presentation component.

## Runtime features and privacy

Closed SDK capabilities are represented by `SdkFeature`. They can be enabled or disabled at runtime:

```kotlin
Engage.sdkFeatures.edit {
    disable(SdkFeature.ANALYTICS)
    disable(SdkFeature.IN_APP)
}
```

Privacy state is observable and persists across launches:

```kotlin
Engage.privacy.optOut()
Engage.privacy.optIn()
```

`optOut()` stops normal synchronization while preserving local state. Use
`optOutAndWipe()` only for an explicit privacy-erasure flow: it clears local Engage data and queues
remote revocation when necessary.

## Development

The repository pins Java through mise:

```shell
mise install
mise run check
```

`mise run check` builds and tests the complete dependency graph and verifies every Maven
publication locally.
