# Engage Android SDK

The official native Android SDK for Engage. The repository is a Gradle multi-project build with
independently consumable artifacts and one atomic release version.

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

Then select only the modules required by the application:

```kotlin
dependencies {
    implementation("com.github.mathias8dev.engage-android:engage-android-core:2.1.0")
    implementation("com.github.mathias8dev.engage-android:engage-android-push-fcm:2.1.0")
    implementation("com.github.mathias8dev.engage-android:engage-android-in-app:2.1.0")
    implementation("com.github.mathias8dev.engage-android:engage-android-message-center:2.1.0")
    implementation("com.github.mathias8dev.engage-android:engage-android-message-center-divkit:2.1.0")
}
```

All modules use the same version. Depending on Core alone does not pull DivKit.

## Development

The repository pins Java through mise:

```shell
mise install
mise run check
```

`mise run check` builds and tests the complete dependency graph and verifies every Maven
publication locally.
