#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Publish a tagged release: the changes since the previous tag plus the
# screenshots captured by CI. Run from a checkout with the release APK
# built and the Paparazzi screenshots named into `screenshots/`
# (release-screenshots.sh).
set -euo pipefail

TAG="${RELEASE_TAG:-${GITHUB_REF_NAME:?GITHUB_REF_NAME or RELEASE_TAG is required}}"
REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
APK="dist/TeslaBatteryBLE-${TAG}.apk"
NOTES="release-notes.md"
SHOTS_DIR="screenshots"
# README-table-style columns: the scan, the car detail, the trip, Settings; light row + dark row.
SHOTS="02-scanning.png 03-car.png 04-trip.png 05-settings.png 02-scanning-dark.png 03-car-dark.png 04-trip-dark.png 05-settings-dark.png"

# A manual release states the flag outright; a pushed tag derives it from the name.
if [ -n "${RELEASE_PRERELEASE:-}" ]; then
  PRE_FLAG=("--prerelease=$RELEASE_PRERELEASE")
elif [ "$TAG" != "${TAG%-*}" ]; then
  PRE_FLAG=(--prerelease)
else
  PRE_FLAG=()
fi

[ -f "$APK" ] || { echo "missing $APK (the release job stages it)" >&2; exit 1; }

for name in $SHOTS; do
  [ -f "$SHOTS_DIR/$name" ] || { echo "missing $SHOTS_DIR/$name (the capture step must succeed)" >&2; exit 1; }
done
# Host the shots as user attachments so the release page keeps only the APK.
SCAN_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/02-scanning.png" "02-scanning-${TAG}.png")"
CAR_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/03-car.png" "03-car-${TAG}.png")"
TRIP_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/04-trip.png" "04-trip-${TAG}.png")"
SETTINGS_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/05-settings.png" "05-settings-${TAG}.png")"
SCAN_DARK_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/02-scanning-dark.png" "02-scanning-dark-${TAG}.png")"
CAR_DARK_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/03-car-dark.png" "03-car-dark-${TAG}.png")"
TRIP_DARK_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/04-trip-dark.png" "04-trip-dark-${TAG}.png")"
SETTINGS_DARK_URL="$(bash .github/scripts/upload-screenshot.sh "$SHOTS_DIR/05-settings-dark.png" "05-settings-dark-${TAG}.png")"

# Notes come from the commits since the previous tag; there is no
# hand-edited changelog, so pull requests never collide on one.
git fetch --tags origin
PREV_TAG="$(git describe --tags --abbrev=0 --match 'v*' "$TAG^" 2>/dev/null || true)"

{
  echo "## Download"
  echo
  echo "**[TeslaBatteryBLE-$TAG.apk](https://github.com/$REPO/releases/download/$TAG/TeslaBatteryBLE-$TAG.apk)**"
  echo
  if [ -n "$PREV_TAG" ]; then
    echo "Changes since [$PREV_TAG](https://github.com/$REPO/releases/tag/$PREV_TAG):"
  else
    echo "Changes:"
  fi
  echo
  if [ -n "$PREV_TAG" ]; then
    bash .github/scripts/changelog.sh "$REPO" "$PREV_TAG..$TAG"
  else
    bash .github/scripts/changelog.sh "$REPO" "$TAG"
  fi
  echo

  echo
  echo "## Screenshots"
  echo
  echo "**Light**"
  echo
  echo "<p><img src=\"$SCAN_URL\" alt=\"Scanning for nearby cars (light)\" width=\"24%\"><img src=\"$CAR_URL\" alt=\"Car detail with the battery reading (light)\" width=\"24%\"><img src=\"$TRIP_URL\" alt=\"Trip card with the progress bar (light)\" width=\"24%\"><img src=\"$SETTINGS_URL\" alt=\"Settings (light)\" width=\"24%\"></p>"
  echo
  echo "**Dark**"
  echo
  echo "<p><img src=\"$SCAN_DARK_URL\" alt=\"Scanning for nearby cars (dark)\" width=\"24%\"><img src=\"$CAR_DARK_URL\" alt=\"Car detail with the battery reading (dark)\" width=\"24%\"><img src=\"$TRIP_DARK_URL\" alt=\"Trip card with the progress bar (dark)\" width=\"24%\"><img src=\"$SETTINGS_DARK_URL\" alt=\"Settings (dark)\" width=\"24%\"></p>"
} > "$NOTES"

if [ ! -s "$NOTES" ]; then
  echo "See the [commit history](https://github.com/$REPO/commits/$TAG)." > "$NOTES"
fi

# Publish the draft "next release" kept by update-draft-release.sh, if any, under
# this tag. Its pre-release checkbox is left as the owner set it.
DRAFT_ID="$(gh api --paginate "repos/$REPO/releases" \
  --jq '.[] | select(.draft and .name == "Next release (draft)") | .id' | sed -n 1p)"
# Skipped when a release for the tag already exists (the draft was published from
# the GitHub UI, which created the tag and triggered this run): that release is
# just edited below.
if [ -n "$DRAFT_ID" ] && ! gh release view "$TAG" > /dev/null 2>&1; then
  gh api -X PATCH "repos/$REPO/releases/$DRAFT_ID" \
    -f tag_name="$TAG" -f name="$TAG" -F draft=false > /dev/null
  # A pushed tag leaves the draft's pre-release checkbox as the owner set it.
  if [ -z "${RELEASE_PRERELEASE:-}" ]; then PRE_FLAG=(); fi
fi

if gh release view "$TAG" > /dev/null 2>&1; then
  gh release edit "$TAG" --title "$TAG" --notes-file "$NOTES" "${PRE_FLAG[@]}"
else
  gh release create "$TAG" --title "$TAG" --notes-file "$NOTES" "${PRE_FLAG[@]}"
fi

# Uploads are flaky; retry before failing the release.
for attempt in 1 2 3; do
  gh release upload "$TAG" "$APK" --clobber && break
  [ "$attempt" -lt 3 ] || { echo "uploading $APK failed after $attempt attempts" >&2; exit 1; }
  sleep $((attempt * 5))
done

# The release is not done until its APK is actually attached.
assets="$(gh release view "$TAG" --json assets --jq '.assets[].name')"
expected="$(basename "$APK")"
printf '%s\n' "$assets" | grep -qx "$expected" || { echo "release $TAG is missing $expected" >&2; exit 1; }
