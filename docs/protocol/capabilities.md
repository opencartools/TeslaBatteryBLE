// SPDX-License-Identifier: AGPL-3.0-only
# Tesla BLE capabilities

Date: 2026-10-04 (proto rev: a4b43c1eff0e09d77deb9f2dce97031141fe8c8a)

Pinned protos: `core/src/main/proto/*.proto` (rev recorded in
`core/src/main/proto/TESLA_COMMIT`). Go reference: `teslamotors/vehicle-command`
at the same rev (paths below are `pkg/vehicle/*.go`, `pkg/protocol/*.md` in
that repo unless stated otherwise).

## Summary (max 6 bullets)

- BLE exposes **two domains**: VCSEC (`DOMAIN_VEHICLE_SECURITY`, locks/closures/
  keys/status, works while asleep) and Infotainment (`DOMAIN_INFOTAINMENT`,
  everything else, car must be awake). Same signed-command protocol also runs
  over Fleet API HTTPS; BLE GATT service `00000211-…`, write `…0212…`, notify
  `…0213…` (see `pkg/protocol/protocol.md`, "Message transports / BLE").
- Strongest battery signals over BLE all come from one message,
  `ChargeState` (`vehicle.proto`): **SOC (`battery_level`, `usable_battery_level`),
  charge limits, charging state, energy added, charger V/A/W, pilot current,
  phases, charge rate, minutes-to-full/limit, cable/charger type, schedules,
  charge-port latch/door, Powershare/outlet fields**.
- What BLE gives that matters for battery tracking without cloud: on-demand
  wake, ~10 s polling of SOC + charge power/current while awake, scheduled
  charging/departure management, charge start/stop/limit/amps — all with a
  **CHARGING_MANAGER** key (least-privilege role).
- What BLE **cannot** give (verified absent from pinned protos by field search):
  pack voltage/current, cell voltages, module/pack temperatures, SoH/degradation,
  usable/full-pack capacity kWh, C-rate, motor power/torque (beyond coarse
  `DriveState.power`), odometer is present but **no trip energy**, no BMS
  diagnostics. Pack-level signals come from Fleet Telemetry (car → cloud
  streaming), not from this protocol.
- Interaction model is strictly **request/response polling**; there is no
  subscribe/notify/stream primitive in the pinned protos. The BLE notify
  characteristic only carries response fragments. VCSEC allows max ~3
  simultaneous BLE connections (shared with keyfobs) and clients should avoid
  simultaneous VCSEC requests (`pkg/protocol/protocol.md`).
- Roles gate commands car-side and may change with firmware (`protocol.md`
  "Roles"): OWNER = everything incl. key management; DRIVER = most commands,
  no key/PIN management; CHARGING_MANAGER = reads + charging commands;
  VEHICLE_MONITOR = reads only; FLEET_MANAGER cannot use BLE at all.

## Readable data (tables: domain | message | fields | notes)

### VCSEC domain (`vcsec.proto`, `pkg/vehicle/vcsec.go`, `pkg/vehicle/state.go`)

| message | fields | notes |
|---|---|---|
| `VehicleStatus` (via `InformationRequest` GET_STATUS = `BodyControllerState`) | `closureStatuses` (8 closures: 4 doors, front/rear trunk, charge port, tonneau), `vehicleLockState`, `vehicleSleepStatus` (awake/asleep), `userPresence`, `detailedClosureStatus.tonneauPercentOpen` | Works over BLE **while infotainment is asleep** (`state.go` doc comment). Our app's always-available poll. |
| `WhitelistInfo` / `WhitelistEntryInfo` (via GET_WHITELIST_INFO / GET_WHITELIST_ENTRY_INFO = `KeySummary` / `KeyInfoBySlot`) | key slots, roles, form factors | Key inventory; OWNER-level visibility. |
| `CommandStatus` / `NominalError` | `operationStatus`, whitelist-operation status, nominal errors | Not data per se; result पता for writes. NFC-tap pairing surfaces as `OPERATIONSTATUS_WAIT`. |

### Infotainment domain (`car_server.proto` `GetVehicleData` → `vehicle.proto`, `pkg/vehicle/state.go` `GetState`)

One `GetVehicleData` category per request (12 getters in pinned
`car_server.proto`; no "get everything" call). All require an awake car
(they route to `DOMAIN_INFOTAINMENT` via `getCarServerResponse` in
`pkg/vehicle/infotainment.go`).

| message (`Get…` → response field) | fields | notes |
|---|---|---|
| `ChargeState` | **see "Battery-relevant reads" below** — the full table | The battery message. Present-but-maybe-unpopulated: fields are `oneof optional_*`; car omits what it has no data for (e.g. `fast_charger_*` when not on DC). |
| `ClimateState` | cabin/outside temps, setpoints, fan, `battery_heater`, `battery_heater_no_power`, preconditioning flags, seat/steering-wheel heat, COP, keeper mode, defrost | Only battery-adjacent bits: `battery_heater` bool + `is_preconditioning`. No pack/battery temperatures. |
| `DriveState` | `shift_state`, `speed`, `speed_float`, **`power` (int32)**, `odometer_in_hundredths_of_a_mile`, active-route fields + `active_route_energy_at_arrival` | `power` is coarse drive power (sign = drive/regen); **not** motor torque/rpm, **not** pack V/I. Odometer present; **no trip/segment energy**. |
| `LocationState` | lat/lon/heading, native/corrected/geo coordinates, elevation, accuracy, homelink, location name | Privacy-sensitive; VEHICLE_MONITOR+ can read. |
| `ClosuresState` | per-door/trunk/window open bools, sunroof, `locked`, `is_user_present`, `remote_start`, valet, sentry state, speed-limit mode, tonneau | Richer duplicate of VCSEC status but infotainment-sourced (awake only). |
| `ChargeScheduleState` / `PreconditioningScheduleState` | schedule windows, buffers, max counts | Backing data for the schedule commands. |
| `TirePressureState` | 4× TPMS pressure (bar) + warnings + RCP values, timestamps | Complete; unrelated to battery. |
| `MediaState` / `MediaDetailState` | artist/title/source/volume/playback status/album/station/elapsed | Complete read side for media commands. |
| `SoftwareUpdateState` | status (installing/scheduled/available/downloading), %, version, times | — |
| `ParentalControlsState` / settings | active, pin set, speed/chill/safety/curfew/browser/theater/arcade flags, limits | — |
| `VehicleState` | currently only `guestMode` | Thin. |
| `ManagedChargingState` + `ChargeOnSolarState*` | charge-on-solar state machine, gateway DIN, Tesla Electric asset id | Prosumer/solar niche. |
| `NearbyChargingSites` (via `GetNearbyChargingSites`) | supercharger list w/ metadata | Query, not vehicle state. |

Also in `common.proto`: `ChargeSchedule`, `PreconditionSchedule`,
`OffPeakChargingTimes`, `PreconditioningTimes`, `ChargePortLatchState`,
`LatLong` — parameter types for the above, not independent reads.

## Battery-relevant reads in detail

All fields below are in `ChargeState`, `core/src/main/proto/vehicle.proto`
(~field 1–178). Every scalar is `oneof optional_*` = **omitted when the car
has nothing to report**, so absence ≠ zero. Field numbers in parentheses.

**Core SOC / limits**

- `charging_state` (1): Unknown/Disconnected/NoPower/Starting/Charging/
  Complete/Stopped/Calibrating (`ChargingState` oneof).
- `battery_level` (114), `usable_battery_level` (115): percent SOC; usable <
  displayed when buffer/limits apply.
- `charge_limit_soc` (104), `charge_limit_soc_std/min/max` (105–107):
  current limit and car-enforced bounds.
- `one_time_soc_limit` (175), `outlet_soc_limit` (162),
  `power_feed_soc_limit` (163): one-shot / V2L-adjacent caps.
- `charge_limit_reason` (157): Unknown/None/Evse/BattTempLow/HighSoc/Cabin.
- `trip_charging` (125), `max_range_charge_counter` (109),
  `supercharger_session_trip_planner` (154).

**Range / energy**

- `battery_range` (111), `est_battery_range` (112),
  `ideal_battery_range` (113) in miles (2-decimal floats); rated vs
  estimated vs ideal.
- `charge_energy_added` (116, kWh 1-decimal), `charge_miles_added_rated`
  (117), `charge_miles_added_ideal` (118). While the car sits plugged in and
  `Complete`, `charge_energy_added` creeps down as the pack drains (47.64 to 47.22 kWh
  over nine hours at 100%); unplugged it holds. The parked-drain code tolerates a fall
  of up to 2 kWh in that state and reads any other change as a session that ran unseen.

**Charger electrical (EVSE-side, not pack-side)**

- `charger_voltage` (119, V), `charger_pilot_current` (120, A),
  `charger_actual_current` (121, A), `charger_power` (122, kW),
  `charger_phases` (134).
- `charge_current_request` (137), `charge_current_request_max` (138),
  `charging_amps` setting echo (149).
- `charge_rate_mph` (126), `charge_rate_mph_float` (156).
- `minutes_to_full_charge` (123), `minutes_to_charge_limit` (142).
- `conn_charge_cable` (28: SNA/IEC/SAE/GB_AC/GB_DC), `fast_charger_type`
  (2: Supercharger/Chademo/Combo/…), `fast_charger_brand` (3),
  `fast_charger_present` (110).

**Port / session / scheduling**

- `charge_port_door_open` (127), `charge_port_latch` (35),
  `charge_port_cold_weather_mode` (136), `charge_port_color` (155,
  LED state incl. flashing green = charging), `charge_cable_unlatched` (159).
- `charge_enable_request` (133), `user_charge_enable_request` (132).
- `scheduled_charging_start_time` (129, epoch s),
  `scheduled_charging_pending` (130), `scheduled_charging_mode` (148:
  Off/StartAt/DepartBy), `scheduled_charging_start_time_minutes` (150),
  `scheduled_departure_time_minutes` (151), `scheduled_departure_time` (31),
  `scheduled_charging_start_time_app` (153), `preconditioning_enabled` (152),
  `preconditioning_times` (45), `off_peak_charging_times` (46),
  `off_peak_hours_end_time` (147).
- `managed_charging_active` (139), `managed_charging_user_canceled` (140),
  `managed_charging_start_time` (141), `managed_charging_state` (158, solar).
- `timestamp` (44) on every sample — use for poll cadence math.

**Powershare / outlet (Cybertruck + Powershare cars; absent elsewhere)**

- `powershare_feature_allowed/enabled/request` (166–168),
  `powershare_type/status/stop_reason` (169–171),
  `powershare_instantaneous_load_kw` (172),
  `powershare_vehicle_energy_left_hr` (173), `powershare_soc_limit` (174),
  `outlet_state/power_feed_state` (160–161), `outlet_time_remaining` /
  `power_feed_time_remaining` (164–165), `outlet_max_timer_minutes` (178),
  `home_location/work_location` (176–177).

**Battery-adjacent, other messages**: `ClimateState.battery_heater` (122),
`battery_heater_no_power` (123), `is_preconditioning` (128);
`DriveState.power` (103, coarse kW, sign indicates drive vs regen) and
`DriveState.active_route_energy_at_arrival` (11, nav prediction only).

## Commands (table: command | domain | role | awake needed | notes)

Role column = minimum role per `pkg/protocol/protocol.md` "Roles" + Go
method notes; car enforces car-side and behavior can vary by firmware
(protocol.md warns capabilities change). Awake column: VCSEC = no;
Infotainment = yes (asleep car returns busy/timeout/internal errors —
`MessageFault_E` in `universal_message.proto`).

| command (Go method → proto) | domain | role | awake | notes |
|---|---|---|---|---|
| Wake car (`wakeupRKE` → `RKEAction_WAKE_VEHICLE`, `pkg/vehicle/vcsec.go`) | VCSEC | DRIVER | n/a (this *is* the wake) | RKE-signed. Our on-demand wake path. |
| Lock / Unlock (`Lock`/`Unlock` → `RKE_ACTION_LOCK/UNLOCK`, `security.go`) | VCSEC | DRIVER | no | RKE-signed, works asleep. |
| Remote drive / auto-secure (`RemoteDrive`, `AutoSecureVehicle`) | VCSEC | DRIVER (remote-drive is sensitive) | no | RKE-signed. |
| Trunk open/close, frunk open, tonneau open/close/stop (`actions.go` → `ClosureMoveRequest`) | VCSEC | DRIVER | no | Frunk has no remote close. |
| Pair key, NFC-tap flow (`SendAddKeyRequest[WithRole]` → `addKeyToWhitelistAndAddPermissions`) | VCSEC | self-bootstrap (unsigned `SIGNATURE_TYPE_PRESENT_KEY`) | no | **Must be BLE** (`ErrRequiresBLE` on Fleet API); needs console NFC tap + UI confirm; `OPERATIONSTATUS_WAIT` while waiting. |
| Add/remove keys, key inventory (`AddKey[WithRole]`, `RemoveKey`, `KeySummary`) | VCSEC | OWNER | no | DRIVER cannot manage keys. |
| Charge start/stop/max-range/standard (`ChargeStart/Stop/MaxRange/StandardRange` → `ChargingStartStopAction`) | Infotainment | CHARGING_MANAGER | **yes** | Core charge control for our role. |
| Set charge limit (`ChangeChargeLimit` → `ChargingSetLimitAction`) | Infotainment | CHARGING_MANAGER | **yes** | Percent int32. |
| Set charging amps (`SetChargingAmps` → `SetChargingAmpsAction`) | Infotainment | CHARGING_MANAGER | **yes** | |
| Charge port open/close (`ChargePortOpen/Close`, also `OpenChargePort`/`CloseChargePort` in `charge.go`) | Infotainment | CHARGING_MANAGER (port) | **yes** | Note: VCSEC `closureStatuses.chargePort` *reads* port state while asleep. |
| Scheduled charging / departure (`ScheduleCharging`, `ScheduleDeparture`, `ClearScheduledDeparture`) | Infotainment | CHARGING_MANAGER | **yes** | |
| Add/remove charge & precondition schedules (6 methods in `charge.go`) | Infotainment | CHARGING_MANAGER | **yes** | |
| Low-power / accessory-power modes (`SetLowPowerMode`, `SetKeepAccessoryPowerMode`) | Infotainment | DRIVER | **yes** | Energy-relevant but not charge control. |
| Climate on/off, temp, seats, wheel, precondition-max, bioweapon, COP, keeper (`climate.go`, ~12 methods) | Infotainment | DRIVER | **yes** | No CHARGING_MANAGER shortcut documented; treat as DRIVER. |
| Sentry on/off (`SetSentryMode`) | Infotainment | DRIVER | **yes** | Keeps car awake — battery-relevant side effect. |
| Flash/honk/windows/sunroof/homelink (`actions.go`) | Infotainment | DRIVER | **yes** | |
| Valet, PIN-to-drive, speed limit, parental controls, guest mode, erase data (`security.go`) | Infotainment | OWNER/DRIVER split (PIN mgmt = OWNER-class) | **yes** | **`SetPINToDrive` is Fleet-API-only** (`ErrRequiresEncryption` unless `FleetAPIConnector`); admin clears exist for some. |
| Media controls, volume, software-update schedule/cancel, vehicle name, nearby chargers (`infotainment.go`) | Infotainment | DRIVER | **yes** | |
| `Ping` (authenticated no-op) | Infotainment | any enrolled key | **yes** | Key/liveness check. |
| Reads: `BodyControllerState` | VCSEC | VEHICLE_MONITOR (read-only role exists for this) | no | |
| Reads: `GetState` × 12 categories | Infotainment | VEHICLE_MONITOR can read; CHARGING_MANAGER reads + charge writes | **yes** | `state.go` doc: Fleet API `vehicle_data` is more efficient when online; BLE `GetState` is one category per round trip. |

## Explicitly NOT available over BLE (signal | why | closest alternative)

Verified by case-insensitive search of all pinned `*.proto` for each term
(no hits) unless a field is cited. "Not in protocol" = no field exists;
"not populated" = field exists but cars may omit it.

| signal | why | closest BLE alternative |
|---|---|---|
| Pack voltage (V) | **Not in protocol** (no `pack_voltage`/BMS field anywhere) | `ChargeState.charger_voltage` = EVSE output, not pack; `charger_power` ÷ `charger_actual_current` ≈ session-average volts while DC charging only. |
| Pack current (A) | **Not in protocol** | `charger_actual_current` (AC/DC session current); `DriveState.power` for coarse drive/regen kW. |
| Cell voltages / min-max spread / imbalance | **Not in protocol** (no `cell`/`brick`/`imbalance` fields) | Nothing. Imbalance is *inferred* weakly from usable-vs-displayed SOC gap or range-vs-SOC slope over many sessions — low confidence. |
| Module/pack/coolant/inlet temperatures | **Not in protocol** (only cabin/outside temps in `ClimateState`) | `charge_limit_reason=BattTempLow`, `battery_heater` on/off, cold-weather port mode — binary proxies. |
| SoH / degradation % / full-pack capacity (kWh) | **Not in protocol** | Track `battery_range` at 100 % (or rated-miles added per SOC %) longitudinally as a degradation proxy; needs user charging to known points. |
| Usable/full nominal energy (kWh) | **Not in protocol** | `charge_energy_added` per session + SOC delta → rough capacity estimate per session. |
| C-rate | **Not in protocol** (derives from pack current ÷ capacity, both absent) | `charger_power` ÷ rated-range-derived capacity estimate — very rough. |
| Motor torque / rpm / inverter signals | **Not in protocol** | `DriveState.power` (coarse), `speed`/`speed_float`. |
| Odometer trip energy / Wh-per-trip | **Not in protocol** (odometer present: `DriveState.odometer_in_hundredths_of_a_mile`; only *predicted* `active_route_energy_at_arrival`) | Delta SOC × capacity-estimate per trip. |
| Charge curve history / session logs on car | **Not in protocol** (no log/curve message) | Poll `charger_power`+SOC at ~10 s while charging and build the curve client-side. |
| BMS faults / isolation / contactor state | **Not in protocol** (no DTC/BMS message; `NominalError` is command errors only) | `charging_state`, `charge_limit_reason`, exhaustively: none. |
| Fleet Telemetry stream (V / I / temps / SoC at Hz) | **Different channel**: car→cloud MQTT/TLS configured via Fleet API, not BLE; no subscribe primitive in pinned protos (no `subscri*`/`stream`/`notify`/`telemetry` hits) | BLE polling (below). |
| Car-initiated push over BLE | **Not in protocol**: strictly request/response (`Send`→`Receiver`; VCSEC multi-message replies are still responses to one request, `protocol.md` "VCSEC application-layer responses") | Poll VCSEC ~10 s asleep, infotainment ~10 s awake. |
| Infotainment data beyond the 12 `GetVehicleData` categories | **Not in protocol**: `VehicleAction.getVehicleData` oneof lists exactly Charge/Climate/Drive/Location/Closures/ChargeSchedule/PreconditioningSchedule/TirePressure/Media/MediaDetail/SoftwareUpdate/ParentalControls | Fleet API `vehicle_data` (cached, online) for the same set in one call. |
| PIN-to-drive set over BLE | Refused client-side: `SetPINToDrive` returns `ErrRequiresEncryption` unless on Fleet API (`security.go`) | Fleet API only; admin clear exists for reset flows. |
| Pre-2021 Model S/X | Protocol unsupported by those cars (README "System overview") | Fleet API only. |

## Constraints (wake, roles, polling, sessions, rate)

- **Wake**: VCSEC (locks, closures, `BodyControllerState`, wake itself) works
  asleep. Every `GetState` category and every car-server action needs
  infotainment awake; asleep cars answer with `MESSAGEFAULT_ERROR_BUSY /
  TIMEOUT / INTERNAL` (`universal_message.proto`) or simply time out. Our
  policy (ADR-0001): never wake to read SOC; wake only on explicit user action.
- **Roles**: enroll least privilege. CHARGING_MANAGER covers our
  read + charge-control needs; locks/climate need DRIVER; key management
  needs OWNER; FLEET_MANAGER keys don't work over BLE at all (`protocol.md`
  "Roles"; `keys.proto` enum). `COMMAND_REQUIRES_ACCOUNT_CREDENTIALS`
  (`universal_message.proto` fault 23) forces some commands to Fleet API.
- **Polling**: request/response only; practical cadence ~10 s VCSEC asleep /
  ~10 s infotainment while awake (matches ADR-0001). Each `GetState` is one
  category per round trip over 256-byte-MTU-chunked BLE (`protocol.md`;
  ADR-0001 framing notes). VCSEC: max ~3 concurrent BLE links total — counted
  per client device, so two apps on one phone share a channel (verified
  in-car, 2026-10-11); avoid parallel VCSEC requests. Faster polling keeps the car awake and drains
  battery — back off when `vehicleSleepStatus` says asleep / charge complete.
- **Sessions**: per-domain ECDH handshakes (VCSEC + Infotainment separately),
  AES-GCM over BLE (HMAC over Fleet API), counters per epoch, clock tracking
  per domain, session caching to skip handshakes (`protocol.md`
  "Authorizing commands", "Caching session state"). Always set
  `FLAG_ENCRYPT_RESPONSE` (firmware 2024.38+ encrypts replies).
- **Rate/reliability**: BLE transport has no TCP guarantees even proxied over
  Fleet API (`protocol.md`); client retries with `RetryInterval` until ctx
  expiry (`vehicle.go` `Send`). `MESSAGEFAULT_ERROR_REQUEST_MTU_EXCEEDED /
  RESPONSE_MTU_EXCEEDED` bound message sizes. Advertising stops at max BLE
  connections (ADR-0001) — failure to see the car may mean full slots, not
  absence.

## Sources (numbered, URLs/paths, accessed 2026-10-04)

1. Pinned protos `core/src/main/proto/vehicle.proto` (esp. `ChargeState`
   fields 1–178, `DriveState`, `ClimateState`, `VehicleData`), `vcsec.proto`
   (`UnsignedMessage`, `RKEAction_E`, `VehicleStatus`), `car_server.proto`
   (`VehicleAction` oneof, `GetVehicleData`, all `*Action` messages),
   `universal_message.proto` (`Domain`, `RoutableMessage`, `MessageFault_E`,
   `Flags`), `keys.proto` (`Role`), `signatures.proto`, `errors.proto`,
   `common.proto`, `managed_charging.proto`; rev
   `core/src/main/proto/TESLA_COMMIT` = `a4b43c1…8c8a`.
2. `https://github.com/teslamotors/vehicle-command/blob/a4b43c1eff0e09d77deb9f2dce97031141fe8c8a/pkg/protocol/protocol.md` — GATT UUIDs, framing/chunk note, domains, roles, VCSEC connection limits, request/response + retry semantics.
3. `…/pkg/vehicle/state.go` — `BodyControllerState` works asleep;
   12 `StateCategory` values; `GetState` doc (BLE one-category vs Fleet API
   combined/cached reads).
4. `…/pkg/vehicle/vcsec.go` — RKE actions (lock/unlock/wake/remote-drive),
   closure actions (trunk/frunk/tonneau), add-key/NFC flow, `OPERATIONSTATUS_WAIT`.
5. `…/pkg/vehicle/charge.go` — charge start/stop/limit/amps/port/schedules/
   solar-mode methods (all `executeCarServerAction` = infotainment).
6. `…/pkg/vehicle/actions.go` — trunk/frunk/tonneau + honk/flash/windows/
   sunroof/port-door methods.
7. `…/pkg/vehicle/climate.go` — ~12 climate methods (all infotainment).
8. `…/pkg/vehicle/security.go` — valet/PIN/speed-limit/sentry/guest/key
   management; `SetPINToDrive` Fleet-API-only (`ErrRequiresEncryption`);
   `SendAddKeyRequestWithRole` BLE-only (`ErrRequiresBLE`).
9. `…/pkg/vehicle/infotainment.go` — `getCarServerResponse` (infotainment
   routing), `Ping`, media, software-update, vehicle-name methods.
10. `…/pkg/vehicle/vehicle.go` — `StartSession(domains)` (VCSEC-only sessions
    avoid waking infotainment), `Wakeup`→`wakeupRKE` on BLE, retry loop.
11. `…/README.md` + `…/cmd/tesla-control/README.md` — protocol scope (climate/
    charging commands), pre-2021 S/X exclusion, BLE pairing flow.
12. `docs/adr/0001-battery-tracker.md` (ADR-0001) — GATT IDs, MTU 256, NFC-tap
    pairing, CHARGING_MANAGER default, ~10 s polling, never-wake-to-read.
13. Negative evidence: `grep -in "pack|cell|…|subscri|stream|telemetry|…"
    core/src/main/proto/*.proto` → no matches (2026-10-04); field
    enumerations above double as positive evidence of what *is* present.

## Confidence & gaps

- **High**: message/field inventory (read straight from pinned protos);
  VCSEC-asleep vs infotainment-awake split (code comments + domain routing);
  no-subscription/no-BMS-fields (exhaustive proto search); role ladder
  (protocol.md).
- **Medium**: exact per-command minimum role (car enforces car-side; Tesla
  documents roles qualitatively — "Charging Manager can read vehicle data
  and authorize commands that affect vehicle charging" — not a per-command
  ACL; firmware can move boundaries. Table marks the conservative reading:
  charge = CHARGING_MANAGER OK; locks/climate/closures-config = DRIVER;
  keys/PIN-admin = OWNER).
- **Gaps / to verify on-car**: which `ChargeState` optionals a given
  model/firmware actually populates (esp. `usable_battery_level`,
  `charge_current_request_max`, Powershare block); asleep-car error code for
  `GetState` (busy vs timeout vs internal) per firmware; practical max poll
  rate before the car stays awake; whether `DriveState.power` updates while
  parked/charging. None of these change the NOT-available list — no firmware
  can deliver fields the protocol has no room for.
