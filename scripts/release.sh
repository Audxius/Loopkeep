#!/usr/bin/env bash
# Tag and push a new release. The version lives only in the tag: CI derives versionName
# (1.2.3) and versionCode (1002003) from it, so there is no file to edit.
#
#   scripts/release.sh            # next patch: v0.1.0 -> v0.1.1
#   scripts/release.sh minor      # v0.1.1 -> v0.2.0
#   scripts/release.sh major      # v0.2.0 -> v1.0.0
#   scripts/release.sh 1.4.0      # exact version
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
bump="${1:-patch}"

[ -z "$(git status --porcelain)" ] || { echo "Working tree is not clean; commit or stash first." >&2; exit 1; }
branch=$(git rev-parse --abbrev-ref HEAD)
[ "$branch" = "main" ] || { echo "Releases are cut from main (you are on $branch)." >&2; exit 1; }
git remote get-url origin >/dev/null 2>&1 || { echo "No 'origin' remote configured." >&2; exit 1; }

git fetch --quiet --tags origin
if git rev-parse --verify --quiet origin/main >/dev/null; then
    [ -z "$(git rev-list HEAD..origin/main)" ] || { echo "origin/main has commits you don't have; pull first." >&2; exit 1; }
fi

last=$(git tag -l 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname | head -1)
last="${last:-v0.0.0}"
IFS=. read -r major minor patch <<<"${last#v}"

case "$bump" in
    patch) next="$major.$minor.$((patch + 1))" ;;
    minor) next="$major.$((minor + 1)).0" ;;
    major) next="$((major + 1)).0.0" ;;
    [0-9]*.[0-9]*.[0-9]*) next="$bump" ;;
    *) echo "Usage: $0 [patch|minor|major|X.Y.Z]" >&2; exit 1 ;;
esac
IFS=. read -r nmaj nmin npat <<<"$next"
if [ "$nmin" -ge 1000 ] || [ "$npat" -ge 1000 ]; then
    echo "minor and patch must stay below 1000 (versionCode = major*1000000 + minor*1000 + patch)." >&2
    exit 1
fi
if [ $((nmaj * 1000000 + nmin * 1000 + npat)) -le $((major * 1000000 + minor * 1000 + patch)) ] && [ "$last" != "v0.0.0" ]; then
    echo "v$next is not newer than $last; Android would refuse the update." >&2
    exit 1
fi

echo "Last release: $last"
echo "New release:  v$next"
echo
echo "Changes:"
if [ "$last" = "v0.0.0" ]; then range="HEAD"; else range="$last..HEAD"; fi
git log --no-merges --format='  - %s' "$range"
echo
read -r -p "Tag v$next and push? [y/N] " reply
[[ "$reply" =~ ^[Yy]$ ]] || { echo "Aborted."; exit 0; }

git push origin main
git tag -a "v$next" -m "Loopkeep v$next"
git push origin "v$next"

repo=$(git remote get-url origin | sed -E 's#^(git@github\.com:|https://github\.com/)##; s#\.git$##')
echo
echo "Pushed v$next. The release workflow builds and publishes it:"
echo "  https://github.com/$repo/actions"
