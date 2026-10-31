# ADR-0008: BLE log envelope

Status: Accepted
Date: 2026-10-08
Related: ADR-0006 (raw ChargeState log, amended here), ADR-0004 (vehicle
identity), `docs/requirements/multi-phone.md`

## Context

- ADR-0006 logs the car's raw `ChargeState`, one file per vehicle, and takes
  time only from the car's own `timestamp`. It allows no custom proto and no
  per-record envelope.
- While connected, the app asks VCSEC for the vehicle status every 10 s,
  asleep or not; it is the one channel that answers without waking the car.
  Until now the readings only reached the in-memory debug log, on change.
- Each reading carries little, but together they are useful data points: when
  the car slept, woke, was locked, and had someone in it. TeslaMate's
  asleep/online states can be mapped from them later, and the parked-drain
  projection needs user presence to tell parked stretches from drives.
- `VCSEC.VehicleStatus` (closures, lock state, sleep status, user presence,
  detailed closures) has no timestamp field, and the reply carries no time
  either, so a raw status record would have no place on the timeline.
- DriveState came next, and more BLE replies will follow. Handling each as
  its own exception to ADR-0006 would not scale; one envelope for all of them
  does. The phone's clock at arrival is also worth keeping for the replies the
  car does stamp: it says when the phone read what the car reported.
- `CarServer.DriveState` carries the shift state, speed, power and odometer
  next to the car's own timestamp and, while the car navigates, the
  destination and the route. Local logging stays raw, so all of it is kept.
  The odometer is what lets the parked-drain projection spot a drive that no
  phone watched: a rise between two readings means the car moved in the gap.
- Tesla's protocol document (`pkg/protocol/protocol.md` in vehicle-command)
  says the app's key role, Charging Manager, can read vehicle data, and
  Tesla's Go SDK requests one state category per `GetVehicleData`
  (`pkg/vehicle/state.go`).
- The status log only bounds where watching stopped: after its last record,
  by up to a heartbeat. The first cut of this ADR tightened that bound by
  logging a held-back status reading when the connection dropped. The owner
  asked to log the connection state as well, in the envelope next to the RSSI
  (2026-10-08): connection events say when the phone's connection came and
  went, which makes the held-back reading redundant, and the signal strength
  hints at how close the phone was to the car for every record.

- Real-car logs could not tell whether a wake or a request came from the app
  or from the owner, nor whether the app was in the background. The owner
  asked to log the app's own commands, raw, with a small reason, and the app's
  own state (2026-10-09). Design constraint: stay simple and easy to move into
  a Room table later, one row per record, raw bytes plus a few columns.

## Decision

- **One envelope for every logged reply and connection event.** `BleRecord`
  (`core/src/main/proto-teslable/ble_record.proto`) holds:
  - `device_timestamp` (field 1): the phone's clock when the record was
    acquired, that is when the reply arrived or the connection changed. It was
    first called `acquired_at`; the rename (2026-10-09) is name-only, field 1
    and its type are unchanged, so stored bytes are identical and nothing
    needs migrating. The new name says whose clock it is, next to the car's
    own timestamps in the payloads;
  - `rssi` (field 5, `optional sint32`): the phone's latest RSSI reading for
    the car at that moment, in dBm, absent when the phone had none;
  - a `payload` oneof with either the car's raw reply, verbatim, nothing
    filtered: `vehicle_status` (field 2), `charge_state` (field 3),
    `drive_state` (field 4), `closures_state` (field 7) or `climate_state`
    (field 8); or a `connection_event` (field 6, `ConnectionEvent`); or one
    of the app's own records (see "Commands and app state" below):
    `command` (field 9), `command_result` (field 10) or `app_state`
    (field 11). Fields are declared in a reading's order: time, signal
    strength, connection, VCSEC status, charge, drive, closures, climate,
    command, command result, app state. Their numbers are the stored format
    and stay as assigned; `rssi`, `connection_event`, `closures_state`,
    `climate_state` and the three app kinds took the next free ones.

  Tesla's vendored protos (`protocol/src/main/proto`, pinned by `TESLA_COMMIT`)
  stay untouched; ours sit in a second Wire source root, so a re-vendor never
  touches it. This amends ADR-0006's "no custom proto, no envelope".
- **Car timestamps are untouched.** Phone time lives only in `device_timestamp`:
  it is never written into a car timestamp field and never stands in for a
  missing one. Records the car stamps keep that stamp as their timeline:
  charge samples stay on `ChargeState.timestamp` and drive samples on
  `DriveState.timestamp`.
- **Every reply is logged raw** (owner, 2026-10-08: keep all raw). A charge
  reply without the car's timestamp or without a level, or a drive reply
  without its timestamp, stays in the log but off the read models; this
  replaces ADR-0006's "not logged".
- **Status is timed by `device_timestamp`**, because VCSEC has no clock. It is the
  one reply kind whose timeline is the phone's clock.
- **Signal strength on every record.** `rssi` is set on every record the app
  writes: status, charge, drive and connection. The controller already reads
  the RSSI on each poll (every 10 s) and faster while the UI shows, and a
  record takes the newest value it holds for that car. That value can be a
  few seconds old, and the first records after a connect can carry the one
  from before the connection came up (the advert's, or the previous
  connection's last). When the phone has no value yet the field is absent,
  never zero.
- **Connection events.** `ConnectionEvent` carries one `State`: `CONNECTED`
  when a connection becomes ready (where the controller also marks the next
  status reading as the first after a connect) and `DISCONNECTED` when a
  connection that had become ready ends. `device_timestamp` is the event time and
  `rssi` the last RSSI before it. Failed connection attempts, which never
  became ready, are not logged, so a car out of range does not fill the log
  with retries. DISCONNECTED covers every way a ready connection ends: the
  car or Android dropping it, and the app closing it (tracking switched off,
  or the car moving to a new address). A killed app logs nothing, so a
  CONNECTED that follows a CONNECTED means the stretch before it ended
  uncleanly, and the status heartbeat bounds where. Connection events are log
  only: no read model and no UI yet.
- **One file per vehicle per kind**, in `filesDir/battery-history/`, framed by
  the same `ProtoLog` codec with the same 20k-record cap and trim per file:
  - `<vehicleId>.charge.pblog` for charge replies;
  - `<vehicleId>.vcsec.pblog` for VCSEC status replies;
  - `<vehicleId>.drive.pblog` for DriveState replies;
  - `<vehicleId>.closures.pblog` for ClosuresState replies;
  - `<vehicleId>.climate.pblog` for ClimateState replies;
  - `<vehicleId>.connection.pblog` for connection events;
  - `<vehicleId>.command.pblog` for the app's commands and the car's refusals
    of them;
  - `app.pblog`, one file for the whole app, for the app's own state. Its name
    is not `<id><suffix>` of any vehicle kind, so no vehicle reader opens it,
    and a test pins that. It has the same cap and trim.

  Each kind has its own suffix and none ends with another, so a reader never
  decodes another kind's file. The backup rules already exclude the
  directory. The store keeps the last logged status record per vehicle,
  seeded from the file on load, so the policy below holds across restarts.
- **Status logging policy** (`shouldLogStatus` in `core`). A reading is logged
  when any of these holds:
  - it is the first reading for the vehicle;
  - it is the first reading after a (re)connect;
  - the raw `VehicleStatus` differs from the last logged one;
  - otherwise, 15 minutes have passed since the last logged one.

  The heartbeat tells a sleeping car with a phone connected apart from a phone
  out of range: while a phone is connected the log gains a record at least
  every quarter hour, so a longer gap means no phone was connected. A reading
  acquired before the last logged one (the phone's clock moved back) is
  logged too.

  Every change is logged, so the last logged status holds until the next
  record or until the connection ends, and the `DISCONNECTED` connection
  event marks where watching ended. That replaces the held-back flush of this
  ADR's first cut (`StatusLogGate` and `HistoryStore.onLinkLost`), which
  logged the newest reading the policy had skipped when the connection
  dropped. The flushed record only repeated an unchanged status at a later
  time; DISCONNECTED says when the connection ended itself, and the store
  needs no gate class, only the last logged record per vehicle, still seeded
  from the file on load. If the app is killed instead, there is no
  DISCONNECTED, and the heartbeat still bounds the end to 15 minutes.
- **DriveState is logged whole, on every reply.** After each successful
  charge reply the controller sends one `GetDriveState`, only while the car
  is awake and an Infotainment session exists; it never starts a session or
  wakes the car. Every reply goes into `<vehicleId>.drive.pblog` verbatim:
  every field, the navigation destination and route included, nothing
  stripped or deduplicated. The log stays on the phone (`PRIVACY.md`), and the
  in-memory debug log shows only a change of shift state, never the location,
  route or destination.
- **Closures and climate are logged whole too** (ADR-0009: Sentry mode and
  climate count as active). Tesla's protos have a `VehicleState` message, but
  it holds only the guest mode and no `GetVehicleData` category returns it.
  The sentry mode state lives in `ClosuresState` (next to the doors, windows,
  lock state, user presence and valet flags), so that is the category the app
  requests and logs, as `closures_state`; `ClimateState` is `climate_state`.
  The controller sends them after each drive reply, in the same awake-only,
  existing-session-only way: charge, drive, closures, climate. Both replies
  go into their files verbatim. Neither holds a location, a route or any
  text; the climate reply is temperatures, fan, heater and defrost settings
  and the keeper mode, and it carries its own timestamp. They have no read
  model yet; the derived flags `ClosuresState.sentryOn` and
  `ClimateState.climateOn` in `StateViews.kt` only feed the poll policy and
  the debug log (a change of either).
- **Location and tire pressure are logged whole too.** `LocationState`
  (`location_state`, field 12; coordinates, heading and the raw GPS elevation
  `geo_elevation`, which no other category carries) goes to
  `<vehicleId>.location.pblog`, and `TirePressureState`
  (`tire_pressure_state`, field 13) to `<vehicleId>.tire-pressure.pblog`. They
  are the last follow-ups of a read, after climate, same awake-only,
  existing-session-only rules: location only when the latest drive reply shows
  D, R or N and the odometer has advanced since the last location read (so every
  10 s read while moving; a car stopped in gear gets one every 5 minutes), tire pressure on the first
  read after a fresh start, once more after about 200 m of driving
  (odometer), and then once 10 minutes have passed
  (`InfotainmentPollPolicy.wantsLocation` and `wantsTirePressure`). Neither
  feeds the poll policy, has a read model or reaches the debug log.
- **Commands and app state** (owner, 2026-10-09). Three more payload kinds
  tell the app's own actions from the owner's and from the car's:
  - `Command` (field 9): one row per request the app sent to the car, raw as
    built, before encryption, with a `Reason`. `oneof request` holds the VCSEC
    `UnsignedMessage` (wake, whitelist and key-slot lookups, add key), the
    CarServer `Action` (the charge, drive, closures and climate reads, and the
    quick-action charge-port and flash-lights commands) or the
    session info request as a `RoutableMessage` (only the domain it is
    addressed to: the public key, routing address and request id are left
    out, and so is the key in an add-key request, though keys are public).
    Reasons come from the call sites: `USER_WAKE`, `USER_REFRESH`,
    `POLICY_ACTIVE`, `POLICY_HOLD`, `POLICY_SAFETY` and `FRESH_START` (the
    poll policy says why a read is due, `InfotainmentPollPolicy.lastReadReason`),
    `SESSION_HANDSHAKE`, `FOLLOW_UP` (drive, closures, climate after the
    previous reply), `PAIRING`, `KEY_LOOKUP` and `USER_ACTION` (the
    quick-actions button). A read that waits for its
    Infotainment handshake keeps the reason it was made for.
  - `CommandResult` (field 10): logged only when the car refuses a command or
    never answers it, holding the reason, the domain and the raw status:
    `ActionStatus` (an Infotainment reply with an error result),
    `CommandStatus` (a VCSEC reply with an error) or `MessageStatus` (a
    message-level fault such as busy or invalid signature, which the car can
    send without any payload), or `timed_out` when no reply came within 15 s
    or the connection ended first. Successes are not logged twice: they are
    already data records. Pairing replies are not logged as results.
  - `AppState` (field 11): an `Event` (screen on and off, app foreground and
    background, tracking service started and stopped) and, with a start, the
    `StartReason` (user, boot, package replaced, or a restart by Android after
    a kill). A killed process logs no stop.

  The routine 10 s VCSEC status poll is left out of the command log: the
  status records imply it and it would flood the file. Every other request
  goes through one method in the controller (`VehicleLink.transmit`), which
  hands the `Command` to `CommandRecorder`; a request is logged once the
  transport accepted it. Records carry `device_timestamp`, plus `rssi` where a
  car is involved (not in `app.pblog`). Foreground and background come from
  the activity's start and stop (a rotation is skipped), because
  `ProcessLifecycleOwner` would need a new dependency; screen on and off from
  a receiver the tracking service registers, so they are logged only while it
  runs; when the service starts, the current screen state is logged too
  (`PowerManager.isInteractive`), as an ordinary screen event after
  `TRACKING_STARTED`. Nothing reads these records back yet.
- **Read models.** `BatterySample`, `StatusSample` and `DriveSample` are
  derived on load from the records (`BleRecord.toBatterySample`,
  `BleRecord.toStatusSample`, `BleRecord.toDriveSample`); nothing is written
  back. Each holds only the fields something reads, so the raw record stays in
  the log: `DriveSample` keeps the car's timestamp, shift state, speed, power
  and the odometer in hundredths of a mile, and the destination and route stay
  in the log only. `rssi` and connection events have no read model yet.
- **Derived values are defined once.** The live state is the car's raw reply
  too: `parseChargeState`, `parseDriveState` and `parseStatusResponse` return
  the Wire message whole, and the screens read plain fields off it. A value
  that needs a rule is an extension property in
  `core/.../protocol/StateViews.kt`: the charging state and the gear as enums
  (`ChargingStateKind`, `ShiftStateKind`, one value per member of the car's
  oneof), the rate fallback, the status flags. The samples, the screens and the
  debug log all use it; no read model re-derives it, and no code compares state
  names as strings.
- **Evolution rules** (repeated at the top of `ble_record.proto`). The logs
  are append-only and long-lived, so the envelope only ever grows additively:
  - New fields and new `oneof` payload kinds only get new field numbers. Old
    records read them as absent, and older app versions skip them as unknown
    fields.
  - Never renumber, reuse or change the type of an existing field. A removed
    field's number and name go under `reserved`.
  - Never move an existing field into or out of the `oneof`.
  - New scalar fields use proto3 `optional`, so absent stays distinguishable
    from zero.

  `rssi` and `connection_event` are the first additions under these rules;
  the command, command result and app state kinds are the latest.

## Consequences

- **Reset (pre-1.0).** Older `<vehicleId>.pblog` files (raw `ChargeState`
  frames, ADR-0006) are no longer read or written, and they are not deleted.
  After updating, the history chart starts empty and fills from new reads. The
  old files stay in app storage, so a one-time import that wraps them in
  `BleRecord`s can come later if wanted; the charge timeline needs no
  `device_timestamp`, so those records could leave it absent. No migration code
  runs. Status logs use the new name `.vcsec.pblog`, so a `.status.pblog`
  left by a pre-merge test build (an earlier record format) is ignored the
  same way instead of hiding every later record. Pre-merge test builds of
  this change also numbered the payload the other way round (charge 2,
  status 3). Those records decode as the wrong kind or not at all: the read
  models skip a record without their payload, and `ProtoLog` skips a complete
  record that fails to decode, so the records after it still read and nothing
  needs deleting.
- **No reset for the signal strength and connection events.** Records
  written before them read `rssi` as absent and carry no connection event; an
  older app version skips both fields as unknown and never opens
  `.connection.pblog`. The flush that went away held no stored state, so
  nothing needs deleting.
- **Clock skew.** Status samples sit on the phone's clock, charge and drive
  samples on the car's. The two agree to within seconds, which is fine for
  this use: status marks stretches of minutes to hours (asleep, parked,
  someone in the car), and nothing joins the logs at second precision. Charge
  and drive records also carry `device_timestamp`, so the offset between the
  clocks can be measured when it matters.
- **Multi-phone merge** (`docs/requirements/multi-phone.md`): a vehicle's
  history is the union of every phone's records, ordered by the car's
  timestamp for charge and drive and by `device_timestamp` for status, with exact
  duplicates collapsing. Two phones watching at once only make the timeline
  denser.
- **Coverage.** Status readings exist only while a phone is connected. The
  gap between a DISCONNECTED and the next CONNECTED is unlogged time, not
  sleep, and the connection file now says where each gap starts and ends.
  After a kill there is no DISCONNECTED, so the gap appears to start at the
  last status record, up to 15 minutes before the real end. At four
  heartbeats an hour plus one record per change, the 20k cap holds months of
  status per car.
- **No reset for the command and app-state kinds.** Older records and older
  app versions skip them as unknown fields; `.command.pblog` and `app.pblog`
  are new files.
- **Privacy.** The RSSI is a rough distance, and with the connection times it
  says when the phone was near the car and when it left. Both stay on the
  phone, outside backups, and `PRIVACY.md` lists them. What leaves the phone
  is decided at export (master plan: the local log stays raw).
- **Drives.** DriveState has its own file, as the `payload` oneof's field 4.
  It carries the car's own timestamp, which is its timeline, so it needs no
  exception, and its odometer separates drives that no phone saw. Detecting
  drives from it, for the parked-drain projection, is not built yet.
- **Traffic.** While the car is awake the drive request doubles the
  Infotainment traffic: one more request per charge poll. The closures and
  climate requests make it four requests per read (charge, drive, closures,
  climate), still only on the reads ADR-0009 allows. The poll cadence is
  tracked in #112 and #113.
- **Size.** A drive record grows while a route is active (destination text,
  route fields), but the 20k cap per file still bounds it. `rssi` adds two or
  three bytes to a record, and the connection file gains two records per
  connection.
