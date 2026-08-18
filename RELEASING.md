# Releasing

Every Android module shares the version declared by `engageReleaseVersion` in
`gradle.properties`.

Android follows Git Flow. `develop` is the integration branch; releases reach `main` only through a
`release/<version>` branch.

1. Make sure `develop` is clean and synchronized with `origin/develop`.
2. Run `mise run release -- <version>`. The task creates `release/<version>`, updates
   `engageReleaseVersion`, and verifies the exact release artifacts locally.
3. Review and commit the version change on the release branch, push it, and open a pull request to
   `main`.
4. Merge the release pull request. CI rebuilds the exact version, creates the matching GitHub tag
   and release, then requests and verifies every JitPack POM.
5. Merge `main` back into `develop` so the release commit and tag lineage remain shared.
6. Release Flutter only after the Android and iOS native versions it pins are available.

Do not push `main` directly and do not create the tag by hand. A normal feature merge into `main`
must not be used as a release shortcut; branch protection should only allow the release PR flow.

Do not create module-specific tags. A single repository tag identifies the exact source graph for
all Android artifacts.

GitHub Actions owns the full build, test, and lint verification. JitPack only runs
`publishToMavenLocal` for the immutable release tag; repeating the complete verification there
wastes the constrained JitPack build environment and can prevent otherwise valid artifacts from
being published.
