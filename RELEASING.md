# Releasing

Every Android module shares the version declared by `engageReleaseVersion` in
`gradle.properties`.

1. Update that property to the new semantic version.
2. Run `mise run check`.
3. Commit and push `main`.
4. CI verifies the complete multi-module build, creates the matching GitHub tag and release, then
   requests and verifies every JitPack POM.
5. Release Flutter only after the Android and iOS native versions are available.

Do not create module-specific tags. A single repository tag identifies the exact source graph for
all Android artifacts.
