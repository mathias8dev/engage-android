# Engage Core for Android

`engage-core` owns the installation lifecycle, offline operation queue, synchronization and the
public façade shared by optional Engage Android modules.

```kotlin
repositories { maven("https://jitpack.io") }
dependencies { implementation("com.github.mathias8dev:engage-android-core:2.1.0") }
```

```kotlin
Engage.start(
    context = applicationContext,
    config = EngageConfig(
        appKey = BuildConfig.ENGAGE_APP_KEY,
        logLevel = EngageLogLevel.VERBOSE,
    ),
)
```

`INFO` is the default logging level. Use `VERBOSE` for local diagnostics and filter Logcat with
tag `Engage`. Startup logs include the technical `installationId`; push credentials, App keys,
binding codes, attribute values and payload values are always excluded.

The SDK never accepts an application user identifier or authentication token. Identity binding is
performed server-to-server with the short-lived opaque code issued by `Engage.installation`.
