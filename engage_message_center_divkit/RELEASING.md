# Releasing

Android modules share one version. Release `engage-android-core` and then
`engage-android-message-center` with the same tag first.

Set `engageReleaseVersion` in `gradle.properties` to the exact semantic version to publish. Normal
builds append the UTC timestamp `-yyMMddHHmm`, for example `2.1.0-2608031542`.

Run `mise run check` for the same checks used by CI. Once the commit reaches `main` and CI is green,
CI creates the tag and GitHub Release if absent and verifies JitPack. `mise run release` is the
manual fallback; it reads `gradle.properties` and takes no version argument.
