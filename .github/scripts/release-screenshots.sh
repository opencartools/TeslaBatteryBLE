#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Name the Paparazzi full-screen snapshots like the release page, README and
# landing page expect: cars list, car view, trip and Settings, light and dark.
#
# Usage: release-screenshots.sh <paparazzi-images-dir> <out-dir>
set -euo pipefail

src="${1:?usage: release-screenshots.sh <paparazzi-images-dir> <out-dir>}"
out="${2:?usage: release-screenshots.sh <paparazzi-images-dir> <out-dir>}"

mkdir -p "$out"
copy() {
  local screen="$1" theme="$2" name="$3"
  shopt -s nullglob
  # Paparazzi prefixes the file with the test class and method; the trailing
  # <screen>_<theme>.png is the stable part.
  local matches=("$src"/*"$screen"_"$theme".png)
  shopt -u nullglob
  if [ "${#matches[@]}" -ne 1 ]; then
    echo "::error::expected one $screen $theme snapshot in $src, found ${#matches[@]}" >&2
    exit 1
  fi
  cp "${matches[0]}" "$out/$name.png"
}

copy screen-cars-list light 02-scanning
copy screen-car-detail light 03-car
copy screen-car-detail-navigating light 04-trip
copy screen-settings light 05-settings
copy screen-cars-list dark 02-scanning-dark
copy screen-car-detail dark 03-car-dark
copy screen-car-detail-navigating dark 04-trip-dark
copy screen-settings dark 05-settings-dark
