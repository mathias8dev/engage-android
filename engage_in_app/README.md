# Engage In-App for Android

`engage-in-app` is the optional Android in-app experience module for `engage-core`. Installing the
artifact registers it automatically; `Engage.start(...)` remains the only initialization call.

The module evaluates synchronized campaigns locally, including schedules, app/screen/event/session
triggers, deterministic locale and allocation variants, and durable frequency limits. It renders
automatic banner, modal and fullscreen overlays one at a time, while embedded placements remain
independent and can be visible concurrently.

## Embedded placements

Compose:

```kotlin
EngageInAppPlacement(
    key = "home.hero",
    placeholder = { HomeHeroPlaceholder() },
)
```

Views:

```kotlin
binding.engageHero.placementKey = "home.hero"
```

Every call for the same placement observes the same hot `StateFlow`. A host records an impression
only after at least half of its rendered surface is actually visible. `RESERVE_SPACE` retains the
last measured height while the placement is empty; `COLLAPSE` removes it.

## Overlay control

Overlays need no display call. An app can pause new presentations temporarily or arbitrate a
candidate without affecting embedded hosts:

```kotlin
Engage.inApp.overlays.pause()
Engage.inApp.overlays.resume()

Engage.inApp.overlays.displayDelegate = InAppOverlayDisplayDelegate { candidate ->
    if (checkoutIsSensitive) DisplayDecision.DEFER else DisplayDecision.ALLOW
}
```

## Content and actions

`SCENE` and `SURVEY` payloads are rendered as DivKit documents. `IMAGE` and `WEB` payloads use their
typed renderer. DivKit and web actions can use these URIs:

- `engage://dismiss`
- `engage://conversion`
- `engage://action/open_order?arguments=%7B%22order_id%22%3A%22123%22%7D`

The last form invokes a handler registered through `Engage.actions`. Visibility, clicks, dismissals
and conversions are queued through the durable core outbox. Disabling `SdkFeature.IN_APP` or opting
out clears placements and closes the current overlay immediately.
