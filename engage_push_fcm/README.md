# Engage Push FCM for Android

Optional Firebase Cloud Messaging transport for `engage-core`. The module registers itself from its
manifest, manages the FCM token and Android notification channels, and never requests notification
permission on behalf of the host application.

```kotlin
repositories { maven("https://jitpack.io") }
dependencies { implementation("com.github.mathias8dev:engage-android-push-fcm:2.1.0") }
```
