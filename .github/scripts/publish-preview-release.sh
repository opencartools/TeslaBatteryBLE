#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Publish the signed APK built from the tip of main on one fixed pre-release
# titled "Preview of main". The release is created once and then kept: every
# push renames its tag to main-<sha7>, repoints it at the new commit and swaps
# the APK, so the releases page shows a single preview entry with its original
# date (tagged pre-releases published later stay newer than it) and the date
# never resets. An update checker that compares tag names (Obtainium's default)
# sees a new version on each push; to follow only this release, filter on the
# title (see docs/install.md). The release carries two APKs of the same commit:
# the debuggable one and the plain release build (both R8-shrunk, same key and
# versionCode). Run from a checkout of main with them at
# dist/TeslaBatteryBLE-preview-debuggable.apk and dist/TeslaBatteryBLE-preview-release.apk.
#
# Needs GH_TOKEN and GITHUB_REPOSITORY.
set -euo pipefail

REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
SHA="${GITHUB_SHA:-$(git rev-parse HEAD)}"
TITLE="Preview of main"
TAG="main-${SHA:0:7}"
APKS=()
for kind in release debuggable; do
  src="dist/TeslaBatteryBLE-preview-${kind}.apk"
  [ -f "$src" ] || { echo "missing $src" >&2; exit 1; }
  apk="dist/TeslaBatteryBLE-${TAG}-${kind}.apk"
  cp "$src" "$apk"
  APKS+=("$apk")
done

git fetch --tags origin main
DESCRIBE="$(git describe --tags --match 'v[0-9]*' "$SHA" 2>/dev/null || echo "$SHA")"
# git describe writes v0.6.4-59-gdc08b2c; drop the "g" so the title reads v0.6.4-59-dc08b2c.
DESCRIBE="$(printf '%s' "$DESCRIBE" | sed -E 's/-g([0-9a-f]+)$/-\1/')"

{
  echo "**Rolling preview of \`main\`.** This release is replaced in place on every push to \`main\` that passes the tests, static analysis and the emulator smoke test: its tag is renamed to the new commit and the APKs are replaced. It carries two builds of the same commit, both minified with R8 and signed with the release key: \`-release.apk\` (like a tagged release) and \`-debuggable.apk\`. They share package, key and version, so either installs over the other without uninstalling."
  echo
  echo "To follow \`main\` in Obtainium: set the release-title filter to \`^Preview\`, and turn on **Include prereleases** and **Fall back to older releases**. Never uninstall to switch signing keys: that wipes the car pairing key."
  echo
  echo "> **Debuggable build:** the app's private storage (including the car pairing key and the logs) can be read with \`adb run-as com.dzid26.teslable\` by anyone with adb access to the phone. Install it only on your own phone. Use \`-release.apk\` if you do not need that."
  echo
  echo "Built from [${SHA:0:7}](https://github.com/$REPO/commit/$SHA) (\`$DESCRIBE\`). Signed with the release key, so it updates an installed release in place."
} > preview-notes.md

release="$(gh api --paginate "repos/$REPO/releases" \
  --jq ".[] | select(.name == \"$TITLE\" and (.draft | not)) | [.id, .tag_name, .target_commitish] | @tsv" | sed -n 1p)"

if [ -z "$release" ]; then
  gh release create "$TAG" "${APKS[@]}" --repo "$REPO" \
    --target "$SHA" --prerelease --title "$TITLE" --notes-file preview-notes.md
  exit 0
fi

IFS=$'\t' read -r id old_tag old_target <<< "$release"

# A run publishes only when its commit descends from the one the current preview
# was built from. That rejects an older run that finishes late, and it does not
# depend on this run being the very tip of main, so one failed push does not
# stop the next from publishing.
if [ "$old_tag" = "$TAG" ]; then
  echo "$TAG is already published."
  exit 0
fi
if git cat-file -e "$old_target^{commit}" 2>/dev/null \
  && ! git merge-base --is-ancestor "$old_target" "$SHA"; then
  echo "The preview is built from ${old_target:0:7}, which ${SHA:0:7} does not descend from; not publishing an older preview."
  exit 0
fi

# The new APK goes up first, so the release is never left without one; the
# retag follows, then the old APK and tag are removed.
for apk in "${APKS[@]}"; do
  for attempt in 1 2 3; do
    gh api --method POST -H "Content-Type: application/vnd.android.package-archive" \
      "https://uploads.github.com/repos/$REPO/releases/$id/assets?name=$(basename "$apk")" \
      --input "$apk" > /dev/null && break
    [ "$attempt" -lt 3 ] || { echo "uploading $apk failed after $attempt attempts" >&2; exit 1; }
    sleep $((attempt * 5))
  done
done
gh api -X PATCH "repos/$REPO/releases/$id" \
  -f tag_name="$TAG" -f target_commitish="$SHA" -f name="$TITLE" \
  -F prerelease=true -F body=@preview-notes.md > /dev/null

new_names="$(printf '"%s",' "${APKS[@]##*/}")"
gh api "repos/$REPO/releases/$id/assets" \
  --jq ".[] | select([.name] | inside([${new_names%,}]) | not) | .id" \
  | while read -r asset; do gh api -X DELETE "repos/$REPO/releases/assets/$asset"; done
gh api -X DELETE "repos/$REPO/git/refs/tags/$old_tag" || true
