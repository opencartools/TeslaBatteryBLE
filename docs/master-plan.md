# TeslaBatteryBLE — Master plan

Status: living document. The checkboxes below are the source of truth for scope.
Created: 2026-10-04 · Maintained alongside every change (see `AGENTS.md`).
Research: `docs/research/` · `docs/reference/fleet-telemetry-vs-ble.md` (live cloud-vs-BLE signal matrix).

## 1. What this is

Android app (Kotlin + Compose) that talks to a Tesla over BLE, starting with
battery SOC and parked drain tracking. No cloud, no account, nothing leaves the
phone. The protocol core (`core/`) is a pure-JVM Kotlin implementation ported
from the Apache-2.0 Go `vehicle-command` and MIT Swift ports; after v1 it
becomes a reusable Tesla BLE library.

Positioning: *"Your Tesla's battery, tracked locally over BLE. No cloud, no
account, nothing leaves the phone."*

## 2. Locked decisions (2026-10-04)

| Decision | Choice | Notes |
| --- | --- | --- |
| License | **AGPL-3.0-only** | Whole repo. Switching to `-or-later` is a one-line change until external contributions land; afterwards it is effectively frozen. |
| Distribution | GitHub Releases + Obtainium now; F-Droid + IzzyOnDroid next; Play later | Signed releases are a prerequisite for F-Droid/IzzyOnDroid. |
| Core library | `:protocol` module (ADR-0013); internal until v1, then publish to Maven Central as `io.github.dzid26:teslable-protocol` | Library-grade docs and API hygiene from now on. |
| Platforms | Android-first; JVM library first; KMP (jvm + apple) only if an iOS app becomes a goal (2026-10-21, ADR-0007) | Keep JVM-only dependencies out of `protocol` and `core`, so the switch stays cheap. |
| Interop | Decisions weigh TeslaMate export/sync and the KMP path (2026-10-08) | Today's limits (90-day / 200k-row retention, no INTERNET permission) are current choices, not commitments. The local log stays raw within its retention window (ADR-0012); filtering happens at export. |
| UX | Phased product pass: P0 polish → P1 product features → P2 extras | |
| Website | GitHub Pages landing for the app; library docs TBD at library release | |
| Money | Donations only (GitHub Sponsors / Ko-fi) | One free FOSS build everywhere; no ads, no telemetry. |

## 3. Definition of done (every change)

- [ ] Builds: `:protocol:test` + `:core:test` + `:app:assembleDebug` (lint when wired).
- [ ] Tests cover new behavior; protocol changes always ship vectors.
- [ ] No secrets, VINs, or keys in logs.
- [ ] User-visible changes are documented (README / docs).
- [ ] No new back-compat or migration code for pre-release states; change formats freely and document any reset (re-pair / reinstall). This ends at the cut-off in ADR-0004 (the first normal, non-prerelease release).
- [ ] Checkboxes here updated; ADR added when an architecture decision was made.

## 4. Validation gates

| Layer | Who / when |
| --- | --- |
| JVM unit tests + static analysis | CI, fully autonomous |
| Emulator smoke test and daily exploration | CI (`.github/actions/emulator`) |
| Real car smoke test | Owner, batched ~10 min per milestone |

Real-car checklist (keep short): pair key → wake car → SOC read → background
tracking survives screen off → notification updates → key survives app restart.

Finished items are not listed below; see `docs/features.md`, the ADRs and git history.

## 5. Phase 0 — Foundation (target: week of Oct 5)

A trustworthy shell around the existing protocol work. No user-facing features.

- [ ] Required checks on `main` (branch protection deliberately deferred while agents push directly).

## 6. Phase 1 — v0.2 "Trust & polish" (target: end of October)

- [ ] Strings extracted to resources (translation-ready).
- [ ] Controller state-machine tests.
- [ ] Store tests.
- [ ] Protocol vectors: nonce/metadata/counter/clock-skew edge cases (negative tests partly done in #41).
- [ ] Baseline Profile.
- [ ] F-Droid submission; IzzyOnDroid submission; document GitHub→F-Droid signature migration (blocked on signed releases).
- [ ] v0.2.0 tagged, signed, with curated changelog.

## 7. Phase 2 — v0.3–0.5 "Product" (target: Nov–Dec)

- [ ] **Pre-1.0 hygiene sweep (owner decision 2026-10-05: remove all)**: delete legacy/migration paths — per-car legacy-key adoption, Keystore-material conversion, format migrations, dead branches. Very old installs re-pair (the key-storage session can restore a device key over adb); coordinate key-file changes with it.
- [ ] Navigation: cars list → car view landed 2026-10-05 (no tabs; last opened car restored). Settings/Logs + ViewModels later.
- [ ] **Battery health v1 (loose, BLE-only)**: rated-range + energy-delta capacity estimates fused with confidence + data-quality flag; session-count "Learning" gate; Service-Mode health-test result logging; habit cards (charge-limit share, AC/DC mix, deep discharges); charge taper / balancing-sawtooth detection; static reference bands from published studies. Never claim cell imbalance, pack temperatures, or month-quantified lifespan without pack-level data.
- [ ] Units choice in Settings: System default / Metric / Imperial (one global setting), defaulting to the device's measurement system (`android.icu.util.LocaleData.getMeasurementSystem`: US → imperial, SI → metric, UK → miles + °C); display-only conversion from canonical miles, with core conversion helpers + tests.
- [ ] **Range backbone** (owner direction 2026-10-05): canonical unit is rated miles; parse `charge_rate_mph` / `charge_rate_mph_float` as the charging slope; stats, charge projection, and drain run in miles; SOC is derived from the learned full-range scale for display only, falling back to the raw int until the scale is pinned. Respects the units setting (display-only conversion from canonical miles). Core landed 2026-10-05: rate parsing, miles-first charge projection, learned full-range scale; the last-charge stats are kept in rated miles and converted to percent at the end, with one decimal from the fitted scale (2026-10-16).
- [ ] **Retention implementation** (after #229 merges): age-based trimming plus count backstop replacing the 20k cap, summary store with a versioned format, estimators reading summaries (ADR-0012).
- [ ] At the cut-off (ADR-0004): version every on-device format, add a golden fixture per shipped format and a reader-accepts-fixture test.
- [ ] Dual range/SOC display: switchable axes and two value hints (miles + SOC) on the car view and history chart.
- [ ] Trip elevation and temperature (#207 follow-up, idea from R 2026-10-21): an elevation profile and stats (gain, loss, peak) per trip and the outside temperature along it. The car's `LocationState` carries `geo_elevation` and `geo_accuracy` but the app does not request it yet, and whether the car answers over BLE is unchecked; the outside temperature is in the climate message the app already logs. Needs a decision on keeping location data (the raw drive log already holds the route coordinates, see ADR-0012).
- [ ] Evaluate Android activity recognition to detect driving, and an opt-in "stay awake while the phone is near and moving" feature (deferred in ADR-0009).
- [ ] **Drive detection** from the DriveState log (odometer deltas across gaps), for the parked-drain projection and TeslaMate drives. The projection part landed 2026-10-09; TeslaMate drives remain.
- [ ] Notifications: charge complete, SOC thresholds, **vampire-drain alert**.
- [ ] Widget (Glance), automation intents (Tasker), CSV/JSON export.
- [ ] **TeslaMate export/sync** (owner direction 2026-10-08): get the phone's BLE history into the owner's self-hosted TeslaMate to fill gaps in its cloud data. Export maps the raw records (ChargeState, DriveState, VCSEC status) onto TeslaMate's vehicle-data shape with the car's timestamps; status readings become asleep/online states. Upstream has no way in for this data yet (its only importer takes data older than the first TeslaMate record); discussion in teslamate-org/teslamate#5496. File export first (share sheet, no new permission); direct sync to the owner's server is opt-in and needs `INTERNET` plus, at targetSdk 37, the runtime `ACCESS_LOCAL_NETWORK` for LAN addresses, with PRIVACY.md and the positioning line updated when it ships. Retention keeps records until they are synced (20k cap, #123).
- [ ] Multiple cars: storage, per-vehicle links, VIN, per-car notifications and the cars/car UI landed (ADR-0004); share redacted diagnostics open.
- [ ] Share redacted diagnostics.
- [ ] Compatibility matrix (car models, vehicle software, Android versions).
- [ ] FAQ / troubleshooting.
- [ ] Coverage thresholds; property/fuzz tests; `tesla-control` interop oracle.
- [ ] Play prep: privacy policy URL, location declaration, closed testing track → go/no-go.

## 8. Phase 3 — v1.0 "Library & launch" (target: Q1 2027)

- [ ] Dokka for the `protocol` library. (`protocol`/`core` → KMP is deferred until an iOS app is a goal; plan in ADR-0007.)
- [ ] Publish the library (Maven Central, `io.github.dzid26:teslable-protocol`, ADR-0013); semver + binary compatibility validator; sample CLI; library documentation.
- [ ] **Battery health v2 (tight, optional OBD)**: BLE OBD dongle support (ELM327/STN), model harness guidance, read-only pack PIDs (nominal/full pack capacity, brick voltage min/max + CAC, pack temperatures), real imbalance card. Purely optional and fully on-device.
- [ ] Governance lite: MAINTAINERS, public roadmap, Discussions, good-first-issues.
- [ ] Launch: F-Droid stable, announcements (r/TeslaLounge, TMC forums, HN), funding live.
- [ ] v1.0 signed release; reproducible-build notes; OpenSSF Scorecard; SBOM.

## 9. Continuous

- [ ] Dependency updates + security fixes.
- [x] Draft release update on every `main` push stays green.
- [ ] Docs kept current with every user-visible change.
- [ ] Signal-matrix drift PRs reviewed (`.github/workflows/signal-matrix.yml`, weekly).
- [ ] Real-car validation per milestone.
- [ ] Community triage once issues arrive.

## 10. Open decisions (defaults so nothing blocks)

- Display name before store submission (default: keep `TeslaBatteryBLE`); `applicationId com.dzid26.teslable` is frozen.
- Library display name (Phase 3); the artifact is `teslable-protocol` on Maven Central (ADR-0013).
- Play go/no-go (Phase 2).
- Library docs site repo/hosting (Phase 3).
- Multi-phone clones (2-3 phones collecting and merging history, optional key cloning): decide later; captured in `docs/requirements/multi-phone.md`.

## 11. Risks

- **Crypto edge cases** → vectors + `tesla-control` oracle; fuzzing later.
- **OEM battery management** kills background BLE → foreground service, documented expectations, FAQ.
- **Store policy** (location/BLE, Tesla trademark) → F-Droid-first, original icon, explicit disclaimer.
- **AGPL deters some library consumers** → accepted trade-off (community norm in this protocol space); revisit only if library traction stalls.
- **Bus factor** → docs-first, `AGENTS.md`, no CLA.
