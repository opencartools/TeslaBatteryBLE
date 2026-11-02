# Unknown proto fields in the car's replies

Date: 2026-10-22
Related: `docs/protocol/capabilities.md` (BLE field catalogue), `docs/research/range-as-soc-signal.md`.

Logs: one phone's `battery-history` pulled 2026-10-22, data ends 2026-10-22 08:55 UTC (charge, drive, climate, closures) and 10:36 UTC (VCSEC). Records: charge 18,713 (from 10-04), drive 16,059 (from 10-08), closures 14,879 and climate 14,004 (from 10-09), VCSEC 5,388 (from 10-08). Car: 2019 Model 3, firmware 2026.38.3.1.
Method: raw wire-format walk against the vendored schema, unknowns tallied per message, then correlated over time with known fields and with the Tesla app's screens.
Upstream: `teslamotors/vehicle-command` main (`vehicle.proto`, `car_server.proto`, `vcsec.proto`) is identical to our vendored copy on 2026-10-22, so none of these fields is defined upstream and no proto update PR is proposed. 130 of the 18,713 charge replies are short partial replies and carry none of the unknowns below.

## Identified

| Message | Field | Wire | Meaning | Confidence |
|---|---|---|---|---|
| ChargeState | 183 | float | Cost of the last charge (4.22 = EUR4.22 in the app) | very high |
| ChargeState | 184 | string | Currency of the last charge ("EUR") | very high |
| ChargeState | 185 | float | Price per kWh of the last charge (0.28) | very high |
| ChargeState | 190 | Timestamp | Time of the last charge: 2026-06-02 14:52:59 UTC, the app's 3:52 PM local time (UTC+1) | very high |
| ChargeState | 195 | varint 0/1 | In park (see below) | high |
| DriveState | 16 | float | Trip meter in miles (see below) | very high |
| VCSEC VehicleStatus | 8 | message { varint 1 } | Awake (2) vs low power (1) (see below) | medium |

All four last-charge fields (183, 184, 185, 190) were confirmed against the Tesla app's Last Charge and Charging Session screens: 15.0940 kWh @ EUR0.28/kWh, EUR4.22, 6 min. They are a summary of the last paid charge, so they stay constant until the next one. The energy (15.094 kWh) and the session time (6 min) are not in any field. Whether 190 is the start or the end of the session is unknown.

## Unresolved

| Message | Field | Values in logs | Notes |
|---|---|---|---|
| ChargeState | 186 | 20, constant | Not the session time (6 min). Equals `powershare_soc_limit`, possibly by coincidence. |
| ChargeState | 193 | 8; 0 in 12 replies | The 12 zeros are 10-10 23:55 to 23:57 UTC, right after a wake, together with missing home/work location. 8 otherwise. |
| ChargeState | 197 | 85, constant | Equals `charge_limit_soc`, which is also 85 throughout. |
| ChargeState | 191, 192, 194, 196, 198 | always 0 | |
| DriveState | 17 | always 0.0 | Float next to the trip meter; maybe a second trip meter. |
| DriveState | 24, 25 | always 0 | |
| ClimateState | 154 | 2 in 14,003 replies, 1 in one | The single 1 is 10-17 14:58:27, the first reply after a long sleep. |
| VCSEC VehicleStatus | 7 | absent, 1, 2, 3 | Appears only while `userPresence` is present, in 636 replies, and only when field 8 is 2. Not a gear (no mapping against `shift_state`). It is 1 on entering, 2 or 3 while driving, 1 again on parking, then absent. Phase unknown. |
| ClosuresState | none | | No unknown fields. |

The charge fields that never change cannot be decoded from one car and one set of settings. Another charge (a different charger or limit) would show which of 186, 193 and 197 move.

## Evidence

**ChargeState 195 is "in park".** Matched against the nearest drive reply within 3 s, it is 1 for P or an invalid shift and 0 for R, N or D in 16,063 of 16,083 replies; the 20 exceptions are lag at the moment of shifting. While charging it is always 1 (10,762 of 10,762). It repeats what `DriveState.shift_state` says but arrives in the charge reply, so a charge-only poll can tell parked from moving.

**DriveState 16 is the trip meter in miles.** `f16` minus the odometer in miles is constant at -75172.95 (spread 0.03 mi) over 16,053 replies, and the slope against the odometer is 1.00001. It is the distance since the trip meter was last reset, in miles whatever the display unit. Six replies read 0.0; five of them come after a gap of more than an hour without drive replies.

**VCSEC VehicleStatus 8 is awake vs low power.** Of 3,045 replies with `vehicleSleepStatus` asleep, 2,888 read 1 and 157 read 2. Of the awake replies, 45 read 1 and 2,298 read 2. At the 60 awake-to-asleep changes, field 8 switches to 1 in the same reply as the sleep status in 35, stays 2 in 23 and was already 1 in two, so it does not lead the sleep status, and it can lag it.

**Climate and Sentry in VCSEC:** neither is identified. With field 8 awake, climate-on is 1 in 1,076 replies and 0 in 489, so field 8 does not tell climate running. Sentry was Off in all 14,879 closures replies, so there was no armed state to correlate with.

## Range fields (known, but they matter here)

`ideal_battery_range` (113) equals `battery_range` (111) in 18,647 of 18,647 replies that carry range (the other 66 carry neither), and `charge_miles_added_ideal` equals `charge_miles_added_rated` in the same replies. `est_battery_range` (112) is never sent. `battery_range` fits linearly in `usable_battery_level` (slope 2.678 mi per %, offset 0.00 mi, residual SD 0.81 mi). The usable level equals `battery_level` in 15,059 replies and is exactly 1 lower in 3,588. No unknown field carries a range. 

## Ideas to settle the open ones

- Run Sentry and climate for a few hours, then pull logs: shows whether VCSEC 7 or 8 move with either.
- Pull logs after the next paid charge: shows whether 183-185 and 190 update and whether 186, 193 or 197 change with it (a different charger, a different charge limit).
