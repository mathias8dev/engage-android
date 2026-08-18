#!/usr/bin/env bash
set -euo pipefail

version="${1:-}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
  echo "Usage: mise run release -- <semantic-version>" >&2
  exit 1
fi

branch="$(git branch --show-current)"
[[ "$branch" == "develop" ]] || {
  echo "A release branch must be cut from develop (current: ${branch:-detached})." >&2
  exit 1
}
[[ -z "$(git status --porcelain)" ]] || {
  echo "Commit or stash every change before preparing a release." >&2
  exit 1
}

origin="$(git remote get-url origin 2>/dev/null || true)"
[[ "$origin" =~ github\.com[:/]mathias8dev/engage-android(\.git)?$ ]] || {
  echo "origin must point to https://github.com/mathias8dev/engage-android.git" >&2
  exit 1
}

git fetch origin develop main --tags
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/develop)" ]] || {
  echo "Local develop must exactly match origin/develop before cutting a release." >&2
  exit 1
}
! git rev-parse -q --verify "refs/tags/$version" >/dev/null || {
  echo "Tag $version already exists." >&2
  exit 1
}
! git show-ref --verify --quiet "refs/heads/release/$version" || {
  echo "Local branch release/$version already exists." >&2
  exit 1
}
! git ls-remote --exit-code --heads origin "refs/heads/release/$version" >/dev/null 2>&1 || {
  echo "Remote branch release/$version already exists." >&2
  exit 1
}

git switch -c "release/$version"
temporary_properties="$(mktemp)"
trap 'rm -f "$temporary_properties"' EXIT
awk -v version="$version" '
  BEGIN { updated = 0 }
  /^engageReleaseVersion[[:space:]]*=/ {
    print "engageReleaseVersion=" version
    updated = 1
    next
  }
  { print }
  END { if (!updated) exit 1 }
' gradle.properties > "$temporary_properties"
mv "$temporary_properties" gradle.properties

./gradlew clean build publishToMavenLocal -PengageVersion="$version"
git diff --check

cat <<EOF
Release branch release/$version is ready and the exact $version artifacts passed verification.
Review and commit gradle.properties, then push the release branch and open a PR to main.
After CI creates tag $version, merge main back into develop.
EOF
