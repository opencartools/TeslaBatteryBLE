---
name: verify
description: Render and visually verify the TeslaBatteryBLE Compose UI, either with headless Paparazzi snapshots or the demo-car emulator capture, then review the images. Use when asked to prove a UI change, inspect a screenshot, or review layout and copy after a render.
---

# Verify TeslaBatteryBLE UI

TeslaBatteryBLE is an Android Compose app. Users touch the car screen, the cars list, the VIN prompt, trip notifications and settings. There are two ways to see that UI without guessing:

- **Paparazzi (primary, no emulator):** renders the real Compose composables for cards, dialogs and screens to PNGs on the JVM. Use it for any layout, copy, color or state change.
- **Demo-car emulator (full flow):** installs a demo build and drives scan → car → settings with `uiautomator`, capturing light and dark screenshots. Use it when the change depends on a real activity, insets, notifications or navigation.

The images are the evidence. Read them. Do not substitute a build log, a passing test, or a self-report for looking at the picture.

## Launch

### Paparazzi

From the worktree root, on Windows:

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.dzid26.teslable.ui.VinPromptSnapshots" --console=plain
```

- Replace `VinPromptSnapshots` with the snapshot class that owns the feature (see `features/`).
- Omit `--tests` to render every snapshot class.
- The task compiles the app; that is expected. First run after a clean worktree is the slow one.
- Ready when it exits 0 and `app/build/reports/paparazzi/debug/index.html` is newer than the run start.

### Emulator

Requires a booted device or AVD and `adb` on PATH.

```powershell
.\gradlew :app:assembleDebug -PdemoCar=true
adb install -r app/build/outputs/apk/debug/app-debug.apk
bash .github/scripts/capture-screenshots.sh app/build/outputs/apk/debug/app-debug.apk .\shots
```

`-PdemoCar=true` makes the car steps run against the simulated car; a plain debug build captures only the scan. The script waits for `sys.boot_completed`, the input service and the package manager, then taps by UI text.

## Doctor

Before blaming the UI, check the instance:

```powershell
adb devices
adb shell getprop sys.boot_completed      # must print 1
adb shell service check input             # must print "Service input: found"
Get-ChildItem app/build/reports/paparazzi/debug/runs | Sort-Object LastWriteTime -Descending | Select-Object -First 3
```

For Paparazzi, the doctor is the report itself: no `runs/` entry from this session means the render did not happen, even if Gradle said "BUILD SUCCESSFUL".

## Drive

1. Pick the feature file in `features/` and the snapshot class it names.
2. Render that class only.
3. Read `app/build/reports/paparazzi/debug/runs/<latest>.js` to map each snapshot name to its file under `images/`. Names end in `_light` / `_dark`.
4. Open the PNGs and review them against the acceptance criteria (see Evidence).
5. For the emulator path, run the capture script and inspect the produced scan/car/settings images.

Prefer named snapshot states over coordinates. If the state you need does not exist, add a snapshot for it in the owning class rather than approximating with an emulator tap.

## Evidence

Capture, at minimum:

- the PNG for each affected state (light **and** dark), at the width the feature appears in;
- for a dialog or card, both the dialog/card and the screen that hosts it;
- the snapshot names you rendered, so the reviewer can reproduce them.

Review visually, in this order:

1. **Alignment** against the adjacent text — the new element's edge must line up with the paragraph or field it belongs to.
2. **Truncation/wrapping** — no clipped labels, no orphan words that change meaning.
3. **Contrast** — text and icons readable in both themes.
4. **Copy** — verbatim match with the approved text.
5. **Touch affordance** — the tappable region is at least 48dp and visibly the thing it acts on.

If an image is missing (narrow width, dark mode, an empty/valid/error state, a dialog over its host), **ask for it** and render it. Do not approve from a partial view. Do not write pixel-diff scripts for this; the visual inspection is the gate.

## Cleanup

- Paparazzi: leave `app/build/reports/paparazzi/debug/` in place; the PNGs are the evidence.
- Emulator: if this run started the AVD, shut down only that one: `adb -s <serial> emu kill`. Never run `adb kill-server` or kill processes by name.
- Remove any scratch output directory you created outside the repo.

## Helpers

None. The Gradle task and the repo's own `capture-screenshots.sh` are the whole harness; there is no wrapper to learn.
