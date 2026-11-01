# Cars list and scan

## Sub-features

The start screen: paired and discovered cars, signal strength, stable row order, the scan spinner/stop control, empty and permission states, and the setup banners above the list.

## How to get to it (user POV)

The app opens on the list. Back from a car returns here. The scan control starts and stops discovery; Android needs location permission for a scan to return results.

## Driving it with Paparazzi

- `SetupBannerSnapshots` — the banners above the list that onboarding does not cover, light and dark.
- The list rows themselves are not a standalone snapshot class; use the emulator capture's **scan** screen when the row layout changes.

## Gotchas

- The emulator capture on a plain debug build stops after the scan; `-PdemoCar=true` is what enables the car and settings steps.
- A missing location permission silently produces an empty scan; check the permission state before judging the list.
- The scan line is a live animation in the app but a still frame in Paparazzi; judge layout, not motion.
