#!/usr/bin/env bash
set -euo pipefail

version="$(sed -nE 's/^engageReleaseVersion[[:space:]]*=[[:space:]]*([^[:space:]]+).*$/\1/p' gradle.properties | head -n 1)"
expected_repository="mathias8dev/engage-android-push-fcm"

if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
    echo "gradle.properties must declare a semantic engageReleaseVersion." >&2
    exit 1
fi

branch="$(git branch --show-current)"
[[ "$branch" == "main" ]] || { echo "Release must run from main (current: ${branch:-detached})." >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "Commit or stash every change before releasing." >&2; exit 1; }

origin="$(git remote get-url origin 2>/dev/null || true)"
[[ "$origin" =~ github\.com[:/]${expected_repository}(\.git)?$ ]] || {
    echo "origin must point to https://github.com/${expected_repository}.git" >&2
    exit 1
}

git fetch origin main --tags
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || {
    echo "Local main must exactly match origin/main before releasing." >&2
    exit 1
}
! git rev-parse -q --verify "refs/tags/$version" >/dev/null || { echo "Tag $version already exists." >&2; exit 1; }

./gradlew clean build publishToMavenLocal -PengageVersion="$version" -PengageDependencyVersion="$version"
git tag -a "$version" -m "Release $version"
git push origin "refs/tags/$version"
