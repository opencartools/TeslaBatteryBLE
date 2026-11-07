# Contributing to TeslaBatteryBLE

Thanks for helping. This project is local-first, privacy-first, and AGPL-licensed;
contributions of all sizes are welcome — code, protocol notes, docs, design, and
bug reports.

## Development setup

1. **JDK 21** (Temurin recommended — CI uses it; JDK 17+ should work)
2. **Android SDK 37** (via Android Studio or `sdkmanager`)
3. Clone and build:

```bash
./gradlew :protocol:test :core:test # protocol and battery tests, no device required
./gradlew :app:testDebugUnitTest    # app tests against the simulated car
./gradlew :app:assembleDebug
```

On Windows use `.\gradlew`. Point `local.properties` at your SDK if Gradle cannot
find it (`sdk.dir=...`).

## Where things live

| Path | What |
| --- | --- |
| `protocol/` | Pure-JVM Tesla BLE protocol (framing, session, pairing, VCSEC/Infotainment). **No Android dependencies** and nothing app-specific — keep it multiplatform-ready and library-shaped. |
| `core/` | Battery domain (history, health, the app's log records) on top of `protocol`. **No Android dependencies.** |
| `app/` | Compose UI, BLE integration, foreground service, storage. |
| `app/src/debug/` | Simulated car for development and screenshots (no hardware needed). |
| `tools/go-fixtures/` | Fixture generator diffed against the pinned `opencartools/vehicle-command` fork in CI. |
| `docs/` | Master plan, ADRs, protocol notes, research. |

## Test expectations

- Every protocol change ships with test vectors. Never hand-edit
  `tools/go-fixtures/expected.txt` — regenerate it from the Go implementation.
- New app logic should be covered by unit tests against the simulated car where
  possible; hardware-only paths should say so in the PR.
- CI must be green: `:protocol:test`, `:core:test`, `:app:testDebugUnitTest`, `:app:lintDebug`,
  `:app:assembleDebug`, and the Go-fixture diff.

## Code style

- Kotlin official style, 4-space indent; match the surrounding code.
- Imperative commit subjects (`Add ...`, `Fix ...`, `Use ...`).
- New source files start with `SPDX-License-Identifier: AGPL-3.0-only`.
- Never log VINs, private keys, session keys, or decrypted payloads.
- Keep refactors out of feature/fix changes.

## Pull requests

1. Fork / branch from `main`.
2. Make the change, add tests and docs (README/plan as applicable).
3. Open the PR and fill in the template. Contributions are licensed under
   AGPL-3.0-only, matching the project (no CLA). Small, focused PRs merge fastest.

Hardware-dependent changes: note in the PR what was tested and what was not. Real-car
validation is batched by the maintainer (see the checklist in the master plan).

## Testing a PR build

Android CI builds a debug APK for every pull request (the `teslable-debug-apk`
artifact).

1. Download the zip from the PR's screenshot comment, or from the Android CI run
   on the PR's **Checks** page (GitHub sign-in needed), and install the APK inside.
2. It installs next to the release app as **TeslaBatteryBLE PR**
   (`com.dzid26.teslable.pr`), with its own data. Its notifications carry a
   "[PR n]" title prefix, a "PR n" subtext and a hollow battery icon, so they
   read apart from the release app's.
3. It needs its own pairing: one key-card tap, and it takes its own key slot on
   the car.
4. Unless the repository has the shared CI debug key below, every run signs with
   a fresh debug key, so uninstall the previous PR build first.

One-time setup (maintainer) for the shared key, so PR builds update each other in
place. Generate a throwaway debug keystore and encode it:

```bash
keytool -genkeypair -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
base64 -w0 debug.keystore
```

Save the output as the repository variable `CI_DEBUG_KEYSTORE_BASE64` (Settings →
Secrets and variables → Actions → Variables). It is a debug key, so a variable is
fine, but never commit the keystore.

## Reporting bugs & requesting features

Use the issue forms in [`.github/ISSUE_TEMPLATE/`](.github/ISSUE_TEMPLATE/). For
security issues, do **not** open a public issue — follow [`SECURITY.md`](../SECURITY.md).
