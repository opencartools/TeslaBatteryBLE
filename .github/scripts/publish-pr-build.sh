#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Maintain one rolling prerelease, "PR builds", that carries the latest debug
# APK of every open pull request: one asset per PR, replaced on each push and
# removed when the PR closes. A fixed release keeps the releases page to one
# entry for PR testing, and its assets are plain .apk links, so a tester can
# install a PR from a phone without a GitHub login and without unzipping an
# Actions artifact. The builds use the .pr application ID and the shared CI
# debug key, so they install next to the app and never touch its pairing key.
#
# Commands:
#   publish <apk>  Replace this PR's APK, prune closed PRs, upload
#   delete         Remove this PR's APK (the PR closed)
#   prune          Remove every APK whose PR is no longer open
#
# publish and delete need PR_NUMBER; publish also needs HEAD_SHA to check the
# APK is the one this run built. All commands need GH_TOKEN and
# GITHUB_REPOSITORY.
set -euo pipefail

REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
TITLE="PR builds"
TAG="pr-builds"
ASSET_PATTERN='^TeslaBatteryBLE-pr([0-9]+)-[0-9a-f]{7}\.apk$'

BODY="**Debug builds of open pull requests.** There is one asset per open PR,
named after its number (\`TeslaBatteryBLE-pr123-abc1234.apk\`). Each is replaced
when the PR is pushed to and deleted when the PR closes.

These are not app releases. They are debug builds (\`com.dzid26.teslable.pr\`,
signed with the shared CI debug key) that install next to the release app and
update each other in place. Do not track this release in Obtainium.

See the [open pull requests](https://github.com/$REPO/pulls)."

find_release_id() {
  gh api --paginate "repos/$REPO/releases" \
    --jq ".[] | select(.name == \"$TITLE\" and (.draft | not)) | .id" | sed -n 1p
}

# Print the release id, creating the release on first use. Two PR runs can get
# here at once; the loser sees the tag already exists, so it looks the release
# up again instead of failing.
release_id() {
  local id
  id="$(find_release_id)"
  if [ -z "$id" ]; then
    if ! created="$(gh api --method POST "repos/$REPO/releases" \
      -f tag_name="$TAG" -f name="$TITLE" -f target_commitish=main \
      -F prerelease=true -f body="$BODY" 2>&1)"; then
      echo "creating the '$TITLE' release failed: $created" >&2
    fi
    id="$(find_release_id)"
  fi
  [ -n "$id" ] || { echo "could not find or create the '$TITLE' release" >&2; return 1; }
  printf '%s\n' "$id"
}

# Print "<asset id><tab><name>" for every asset of the release.
assets() {
  gh api --paginate "repos/$REPO/releases/$1/assets?per_page=100" \
    --jq '.[] | [.id, .name] | @tsv'
}

# A cleanup can race another run deleting the same asset; a delete that is
# already gone is a success, and the next prune retries a real failure.
delete_asset() {
  gh api --method DELETE "repos/$REPO/releases/assets/$1" > /dev/null 2>&1 || true
}

delete_pr_assets() { # <release id> <PR number>
  local id name
  assets "$1" | while IFS=$'\t' read -r id name; do
    [[ "$name" =~ $ASSET_PATTERN ]] || continue
    [ "${BASH_REMATCH[1]}" = "$2" ] || continue
    delete_asset "$id"
  done
}

prune() { # <release id>
  local open id name
  open="$(gh api --paginate "repos/$REPO/pulls?state=open&per_page=100" --jq '.[].number')"
  assets "$1" | while IFS=$'\t' read -r id name; do
    [[ "$name" =~ $ASSET_PATTERN ]] || continue
    if ! grep -qx "${BASH_REMATCH[1]}" <<< "$open"; then
      echo "removing $name: PR ${BASH_REMATCH[1]} is not open"
      delete_asset "$id"
    fi
  done
}

pr_state() {
  gh api "repos/$REPO/pulls/$1" --jq .state
}

publish() {
  local apk="${1:?usage: publish-pr-build.sh publish <apk>}"
  local pr="${PR_NUMBER:?PR_NUMBER is required}"
  local sha="${HEAD_SHA:?HEAD_SHA is required}"
  local expected="TeslaBatteryBLE-pr${pr}-${sha:0:7}.apk"
  local id attempt state

  [ -f "$apk" ] || { echo "missing $apk" >&2; exit 1; }
  [ "$(basename "$apk")" = "$expected" ] || {
    echo "the artifact is $(basename "$apk"), expected $expected" >&2
    exit 1
  }

  id="$(release_id)"
  # A run that started before the merge can reach this point after the PR
  # closed; it must not leave an asset behind. A state that cannot be read is
  # not a close: fail and keep the previous APK rather than deleting it.
  state="$(pr_state "$pr" 2>/dev/null || true)"
  case "$state" in
    open) ;;
    closed)
      echo "PR #$pr is no longer open; not publishing"
      delete_pr_assets "$id" "$pr"
      return 0
      ;;
    *)
      echo "could not read the state of PR #$pr; not publishing" >&2
      exit 1
      ;;
  esac

  delete_pr_assets "$id" "$pr"
  prune "$id"

  for attempt in 1 2 3; do
    if gh api --method POST -H "Content-Type: application/vnd.android.package-archive" \
      "https://uploads.github.com/repos/$REPO/releases/$id/assets?name=$expected" \
      --input "$apk" > /dev/null; then
      echo "published $expected"
      return 0
    fi
    [ "$attempt" -lt 3 ] || { echo "uploading $apk failed after $attempt attempts" >&2; exit 1; }
    sleep $((attempt * 5))
  done
}

delete_pr() {
  local pr="${PR_NUMBER:?PR_NUMBER is required}"
  local id
  id="$(find_release_id)"
  if [ -z "$id" ]; then
    echo "the '$TITLE' release does not exist; nothing to delete"
    return 0
  fi
  delete_pr_assets "$id" "$pr"
  prune "$id"
}

case "${1:-}" in
  publish) shift; publish "${1:-}" ;;
  delete) delete_pr ;;
  prune)
    id="$(find_release_id)"
    if [ -n "$id" ]; then prune "$id"; else echo "the '$TITLE' release does not exist"; fi
    ;;
  *)
    echo "usage: publish-pr-build.sh publish <apk> | delete | prune" >&2
    exit 2
    ;;
esac
