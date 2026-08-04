# Releasing

Set `engageReleaseVersion` in `gradle.properties` to the exact semantic version to publish. A normal
local or CI build appends the UTC timestamp `-yyMMddHHmm`; for example, `2.1.0-2608031542`.

Run `mise run check` for the same checks used by CI. Once the commit reaches `main` and CI is green,
CI creates the tag and GitHub Release if absent, then verifies the JitPack POM. `mise run release`
provides the equivalent manual fallback from a clean local `main`; it reads the version from
`gradle.properties` and takes no version argument.
