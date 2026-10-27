# Range as an online SOC signal: what the car reports, and what it can tell us

Date: 2026-10-05
Related: `docs/research/battery-health-methods.md` (method taxonomy), `docs/reference/fleet-telemetry-vs-ble.md` (signal matrix), `docs/protocol/capabilities.md` (BLE field catalogue).

Question: can the float rated range give sub-percent SOC online, without charging to 100%? This rework supersedes the earlier range-at-100 framing for `bestSocPercent`: active 100% calibration is out of scope (owner decision 2026-10-05). The range-at-100 question stays answered below as a by-product. Owner direction (2026-10-05) broadens this: rated miles become the backbone unit (section 6), with SOC derived at display time.

## 1. What the car reports (raw inventory)

### BLE (`ChargeState`, `core/src/main/proto/vehicle.proto`)

| Field | # | Proto | Meaning | At 100%? |
|---|---|---|---|---|
| `battery_range` | 111 | L318 | Rated range (float, 2 dp — L317) | No — current SOC |
| `est_battery_range` | 112 | L319 | Estimated range (2 dp); consumption-projected (TSV L90) | No — current SOC |
| `ideal_battery_range` | 113 | L320 | Ideal range (2 dp); idealized constant, often absent | No — current SOC |
| `battery_level` | 114 | L322 | Displayed SOC % (int32) | SOC, not range |
| `usable_battery_level` | 115 | L323 | Usable SOC % (int32); can sit below displayed | SOC, not range |
| `charge_limit_soc` | 104 | L309 | Charging target %; 100 only if the owner set it | Target, not range |
| `charge_energy_added` | 116 | L326 | kWh added this session (1 dp — L325); 0 when idle | Session delta |
| `charge_miles_added_rated` / `_ideal` | 117–118 | L327–328 | Rated/ideal miles added this session | Session delta |
| `charge_rate_mph` / `charge_rate_mph_float` | 126 / 156 | L339 / L378 | Charging speed | Rate, not range |
| `minutes_to_full_charge` / `minutes_to_charge_limit` | 123 / 142 | L335–336 | Time estimates | Time, not range |
| `max_range_charge_counter` | 109 | L314 | Count of range-mode charges | Counter, not range |
| `powershare_vehicle_energy_left_hr` | 173 | L399 | Powershare runtime hours | Not traction range |

Re-verified 2026-10-05: grepping this proto for `range`, `energy`, `capacity`, `nominal`, `full` adds nothing — there is no full-pack energy, no capacity, and no `range_at_100` field in BLE.

### Cloud (Fleet Telemetry, `tools/signal-matrix/upstream/fleet-telemetry/vehicle_data.proto` + `.../tesla-docs/available-data.tsv`)

| Signal | # | Proto | TSV | Meaning | At 100%? |
|---|---|---|---|---|---|
| `RatedRange` | 32 | L42 | L170 | "officially rated range … given its current SOC" | No |
| `EstBatteryRange` / `IdealBatteryRange` | 40 / 41 | L50–51 | L90 / L117 | Estimated ("takes driving conditions into account") / ideal ("assuming ideal conditions") | No |
| `Soc` / `BatteryLevel` | 8 / 42 | L18 / L52 | L210 / L10 | Usable / displayed SOC; TSV types are `real` (sub-percent resolution unverified) | SOC |
| `EnergyRemaining` | 158 | L168 | L89 | Nominal energy remaining (kWh) | Current energy |
| `NominalFullPackEnergyKwh` | 263 | L293 | absent | True full-pack energy (kWh); proto L288: fields 260–269 first available in firmware 2026.32 | Capacity in kWh, not miles |
| `BmsFullchargecomplete` | 3 | L13 | L12 | "Indicates BMS is fully charged" | Marker, not range |
| `ChargeLimitSoc`, `DCChargingEnergyIn`, `ACChargingEnergyIn` | 38, 34, 36 | L48, L44, L46 | L25, L41, L2 | Limit; battery-measured energy (usable for AC and DC); charger-measured AC | Not full range |

No range-at-100 signal. Fleet Telemetry also needs an owner account plus a self-hosted server; the app cannot read it today.

### Service Mode / in-car

- Controls > Service > **Battery Health** (and the longer Battery Health Test) reports a state-of-health % / capacity-vs-new after a controlled cycle; no API — the result is read off the touchscreen and typed in. Conditions are secondhand (section 5).
- The car's own range display at 100% is the only direct "range at 100%": it is `battery_range` rendered at `battery_level = 100`.

## 2. The online signal: rated range → finer SOC

### 2.1 Inversion

`bestSocPercent (future) ≈ ratedRangeMiles / fullRatedRangeMiles × 100`

- **Resolution.** `battery_range` is a 2-dp float (proto L317). 0.01 mi over a ~300 mi full range is ~0.003% SOC — far finer than the 1% int, if the underlying value is continuous.
- **Basis.** Our matrix reads rated range as "remaining energy over the trim's rated consumption constant" (`docs/reference/fleet-telemetry-vs-ble.md` L89, `tools/signal-matrix/mapping.json` L43), and `battery-health-methods.md` (rated-range method) cites third-party [1] that rated miles are linearly proportional to capacity via a fixed Wh/mi constant. Neither is a Tesla statement (section 5).
- **Unknown.** Whether the car computes `battery_range` from the integer SOC (so it steps in ~3 mi quanta) or from a finer/smoothed internal SOC (so it drifts continuously). The parked test in section 3 settles it; the whole approach hinges on that.
- **Observed (2026-10-05, parked).** The level held at 77 while `ratedRangeMiles` fell 206.93877 → 206.85713 → 206.61224 over ~2.6 h (0.33 mi; ~0.12% SOC at a ~268.5 mi scale; ~0.13 mi/h). So the rated range does carry sub-percent information, and the drops were ~1× and ~3× a ~0.0816 mi step (origin unknown).

### 2.2 Why not estimated (or ideal) range

- `est_battery_range` is projected with recent consumption ("Takes driving conditions into account", TSV L90), so it is not a single-valued function of SOC and cannot be inverted for SOC; parked, it can move with the consumption basis instead of charge.
- `ideal_battery_range` uses an idealized constant (TSV L117), is legacy and often absent; not a SOC or capacity signal.

### 2.3 The scale (`fullRatedRange`) without a 100% charge

The car never reports its 100% range, and the new-car EPA figure is the SoH denominator, not the scale the car uses to render today's rated range — do not use it for this inversion. Worked example: the observed ~268 mi scale (section 2.1) against a 2019 Model 3 Performance's EPA-new 310 mi (20-inch wheels were standard that year, EPA via fueleconomy.gov) is ~86.5% of new; a reported pack health of ~85% agrees within ~1.5 points, so the learned scale cross-checks the car's own SoH. Learn the scale online instead:

- Track `r = ratedRange / (level / 100)` over time. The integer level makes r fluctuate with rounding; the true scale k sits at:
  - the minimum r if the level is floor-rounded (r ≥ k),
  - the maximum r if ceiling-rounded (r ≤ k),
  - a per-sample bracket if nearest-rounded: `r·L/(L + 0.5) < k ≤ r·L/(L − 0.5)`, intersected across samples.
  Consistency matters more than the choice; the observed r at level 77 spans 268.33–268.75 (floor: k ≤ 268.33; ceiling: k ≥ 268.75; nearest: ~267–270).
- Determine the rounding empirically from r at level transitions (it jumps upward by ~1/L at a downward step in all three cases, at different offsets).
- Tesla may render the rated display from a fixed rated constant rather than true remaining energy (community reports say the battery indicator overstates range versus route planning), so k may be constant over time; tracking r over weeks shows whether k drifts or steps with firmware.
- `charge_miles_added_rated / ΔSOC` over sessions is a second estimate of the same scale.
- A natural 100% charge is a free anchor if it happens; it is never required.

### 2.4 Secondary: state of health

- The rated-range and energy-delta SoH methods stay as described in `battery-health-methods.md`; finer SOC also sharpens the energy-delta endpoints. No active 100% procedure.

## 3. Investigation plan (online, no 100% charge)

### What is logged

History: one series per vehicle in Room's `history.db` (ADR-0014; before that, one append-only `files/battery-history/<vehicleId>.charge.pblog`) — `BleRecord` envelopes (ADR-0008) holding the phone's `device_timestamp` and the raw `ChargeState`, so every field the car reports is kept (level, usable level, rated/est/ideal range, energy added, miles added, charge rates, charger power/voltage/amps, the car's timestamp). Every SOC read is logged, with no deduplication; each file keeps its newest 20k records, the in-memory cache the newest 20k overall; pre-store CSV history and the earlier raw `<vehicleId>.pblog` files are not migrated (pre-1.0 resets).

Export: the debug broadcast in `docs/reference/log-pull.md` writes a zip with a framed `<vehicleId>.charge.pblog` (debuggable build), or the owner shares the files; `ProtoLog.decode(bytes, BleRecord.ADAPTER)` in `core` reads them back, with the raw `ChargeState` in each record's `charge_state`.

### A. Parked behavior of rated range (the key test)

- While parked and untouched, capture reads over hours/days. Prefer natural app opens; each BLE wake draws energy and can perturb drain, so note wake times.
- Compare `ratedRangeMiles` at constant `batteryLevel` / `usableBatteryLevel`:
  - range changes within a constant integer step → sub-percent information exists; quantify the step (0.01 mi? 0.1? ~3?) plus drift rate (mi/h) and temperature sensitivity;
  - range only moves when the integer steps → no resolution gain; stop and rely on the int SOC.
- Also compare the car's displayed Rated range with `ratedRangeMiles` while parked.
- **First result (2026-10-05):** the range moved within a constant integer step while parked (section 2.1) — sub-percent information exists. Next: more levels, the rounding verdict, and the scale bracket.

### B. Driving / charging reads

- Consecutive reads across drives and charge sessions: plot ΔratedRange vs Δlevel; check for within-step movement and hysteresis.

### C. Scale fit

- Fit `fullRatedRange` from (ratedRange, level) pairs; residuals expose quantization and BMS noise; compare with the EPA baseline; check whether displayed or usable level fits better.
- Cross-check with `chargeMilesAddedRated / ΔSOC`.

### Success criteria

- Effective SOC resolution from range is materially better than the 1% floor, reproducibly.
- The inverted SOC is consistent across sessions (within a stated tolerance) and does not track consumption (unlike `est`).

## 4. Logging state

The app records raw car fields only (12-column CSV); the car view shows the displayed SOC and rated range, and stats derive from the raw fields at runtime. The logging change that made this possible landed with PR #67 and was reworked to raw-only on `work/raw-history`.

## 5. Assumptions / unverified

Secondhand or inferred; none of these are pinned to a Tesla statement yet:

- **Rated-range definition and linearity** ("remaining energy over the trim's rated consumption constant", proportional to SOC): our matrix wording plus third-party reports (`battery-health-methods.md` [1]); the TSV only says "officially rated range … given its current SOC" (L170). The parked test in section 3 can confirm both.
- **Buffer semantics** (`usable_battery_level` excludes a bottom buffer; rated miles follow usable energy): inferred from the field pair and community sources.
- **Cloud sub-percent SOC** (`Soc` / `BatteryLevel` are `real`): the type allows decimals; actual resolution is unverified.
- **BMS internal SOC resolution** behind the 2-dp range: unknown; this is the central open question.
- **Service-Mode conditions** (<20% SOC, AC ≥5 kW, up to 24 h; rate-limited): secondhand (`battery-health-methods.md` [7][8]).
- **`charge_miles_added_rated` derivation** (energy added / rated constant): inferred.
- **Error percentages** (±2–5% single reading, ±1–2% trended; ±3–5% per session, ±1–3% averaged): from `battery-health-methods.md`, itself secondhand.
- **`est_battery_range` error shape** (largest after highway/cold drives): secondhand.
- **Rated range's effective precision**: the proto annotation says 2 decimals (L317), but the wire float carries more digits (observed 206.93877, 206.85713); which digits are meaningful is unknown.

Verified raw, for contrast: field numbers and decimal annotations (proto lines above), the TSV descriptions, the firmware 2026.32 note (proto L288), and `charge_energy_added` measured at the battery (TSV L41).

## 6. Range as the backbone (owner direction)

Owner direction 2026-10-05: make rated miles the primary unit everywhere — stored, derived, trended — and convert to SOC only at display time. `charge_rate_mph` / `charge_rate_mph_float` (proto L339 / L378) is the car-reported charging slope in miles per hour, so charging progress does not need to be differentiated from integer SOC. A follow-up UI can switch axes or show both units (dual axes, two value hints).

### Why it fits

- Rated range is already the finest raw signal (section 2.1: sub-percent movement observed); SOC ints are the coarse, derived quantity.
- The car's default display is Rated, so the backbone matches what the owner sees and sidesteps the usable-vs-displayed SOC convention for trend math.
- Charging slope is reported directly: `charge_rate_mph_float` (float) with the int `charge_rate_mph` as fallback; no sample differencing needed for an ETA.
- `charge_energy_added / charge_miles_added_rated` gives the rated constant (kWh/mi) directly, and `charge_miles_added_rated / ΔSOC` gives the full-range scale — both already logged raw.
- It respects the planned units setting: canonical miles internally, display-only conversion (the units item's rule).

### Design

1. **Storage stays raw** (no format change now): rated/est/ideal miles, both SOC ints, session energy and miles-added. Add `chargeRateMph` / `chargeRateMphFloat` to raw logging with the storage rework (or a deliberate format bump), not in the frozen PR #74 format.
2. **Model**: read `charge_rate_mph` / `charge_rate_mph_float` off the raw `ChargeState` (`ChargeState.chargingMph` in `StateViews.kt`: float preferred, int fallback, null when absent or implausible). Keep SOC ints for anchors and the rounding verdict.
3. **Stats and projections in miles**:
   - `ChargeStats`: start/current/min/max in rated miles (Float), `usedMiles` instead of `usedPercent`.
   - `ChargeProjection`: ETA from `charge_rate_mph_float` when charging and plausible, else from ΔratedRange/Δt over the trailing run; target = `chargeLimit / 100 × fullRatedRange` miles (needs the learned scale; keep the percent-based projection as fallback until it is pinned).
   - Drain/vampire tracking: mi/h from rated-range deltas.
4. **Display**: canonical miles; the units setting converts to km for metric (charge rate mi/h ↔ km/h). SOC is an output: `soc = ratedMiles / fullRatedRange × 100` once the scale is learned, with the raw int SOC as the fallback until then.
5. **UI follow-up** (master-plan item): dual axes / two value hints (miles and SOC) on the car view and history chart.

### What breaks / needs care

- **Scale dependency**: mile-denominated stats are self-contained, but SOC display and mile-target projections need the learned `fullRatedRange`. Until the parked/rounding work pins it, keep the int-SOC path as fallback — do not gate the UI on the scale.
- **EPA/trim baseline**: still required for SoH (the denominator), but no longer for day-to-day SOC math.
- **Units preference**: canonical miles already planned; the conversion helpers must add mi/h ↔ km/h for charge rate, and the UK case (miles + °C) must not flip range to km.
- **est/ideal range**: keep raw and out of the backbone; est can later feed a secondary "real-world range" hint, ideal stays completeness-only.
- **Int SOC anchors**: keep logging them — they anchor the scale/rounding verdict and the fallback display.
- **`charge_rate_mph_float` availability**: only meaningful while charging; presence/plausibility (0, stale, AC vs DC) is unverified and needs real logs (and raw logging first).
- **ChargeProjection is user-visible**: the ETA basis changes; add tests and keep the fallback path.

## 7. Raw sources

- `core/src/main/proto/vehicle.proto` L306–L399 (charge state fields, decimal annotations at L317 and L325).
- `tools/signal-matrix/upstream/fleet-telemetry/vehicle_data.proto` L13–L293 (signal numbers; firmware note L288).
- `tools/signal-matrix/upstream/tesla-docs/available-data.tsv` L2, L10, L12, L25, L41, L89–L90, L117, L170, L210.
- `docs/reference/fleet-telemetry-vs-ble.md` L89 and `tools/signal-matrix/mapping.json` L43 (our rated-range wording, not Tesla).
- `docs/research/battery-health-methods.md` (method taxonomy, third-party citations).

## 8. Scale fit: slope of the level steps (2026-10-15)

Dividing rated miles by the integer level (section 2.3) bakes the car's rounding
into the scale, with an error of up to 0.5 / level. TeslaMate history (3.8k
samples, Oct 3-7, one car) cannot name the rounding mode either: ceil, nearest
and floor fit equally well, each with a different scale (268.5 / 266.8 /
265.3 mi, a 1.2% spread).

The spacing between level steps is rounding-independent. `FullRangeScaleEstimator`
now fits `miles = offset + slope x level` to the rated miles at which the level
changes by one (boundary at `level + 0.5`), weighting each boundary by how
tightly consecutive reads bracket it, over the newest 80 boundaries with
outlier rejection. `fullRangeMiles` is `100 x slope` (about 269 mi for the
TeslaMate data, 2.69 mi per 1%); the offset absorbs the rounding mode and any
buffer, and fine SOC is `(miles - offset) / slope`. This is the single
definition of full range: the session-swing and single-reading variants
(issue #122) are removed, and the health card's full range, capacity and rated
SoH use the fit.
