# Compatibility

What TeslaBatteryBLE works with and what to expect. Anything not verified on
real hardware is marked "not verified" rather than assumed.

## Phones

| Requirement | Value |
| --- | --- |
| Android version | 8.1+ (API 27) |
| Radio | Bluetooth LE |
| Permissions | Bluetooth scan/connect (Nearby devices); location when a scan needs it, since Android withholds beacon-style advertisements without it (device location is never read or stored); notifications; foreground service |

## Vehicles

| Vehicle | Supported |
| --- | --- |
| Model 3 (all years) | Yes |
| Model Y (all years) | Yes |
| Model S/X 2021+ (Palladium) | Yes |
| Model S/X pre-2021 | No — unsupported by the BLE protocol |
| Cybertruck | Not verified |

## Key role

The app enrolls with the `CHARGING_MANAGER` role by default (least
privilege): enough for vehicle-data reads plus charge control (start/stop,
limit, amps, port, schedules). Locks and climate need `DRIVER`; key
management needs `OWNER`. Higher roles are explicit opt-in.

## What works asleep vs awake

| Data | Car state needed |
| --- | --- |
| VCSEC status (lock state, closures, sleep status, presence, key info) | Works while asleep — the always-available channel |
| Charge state (SOC, range, charger power/current, schedules) and all charge commands | Car must be awake |

The app never wakes the car just to read SOC; wake happens only on explicit
user action.

## Known constraints

- Up to three cars connect at once, one BLE link per car
  ([ADR-0004](adr/0004-per-vehicle-links.md)).
- The car stops BLE advertising when its BLE connection slots are full, so a
  missing car in the scan list can mean full slots rather than out of range
  (VCSEC allows roughly 3 simultaneous BLE links). One channel is reserved per
  client device, not per app: two apps on the same phone share the phone's
  link, so TeslaBatteryBLE adds no channel when installed alongside Tesla's
  phone-key app (verified in-car, 2026-10-11).
- Polling is request/response only (no push): roughly 10 s VCSEC cadence
  while tracking, and roughly 10 s Infotainment polling while the car is
  awake (e.g. while charging). Faster polling can keep the car awake.
- `GetVehicleData` returns one category per request.
- Which optional charge-state fields a given model/firmware populates varies
  (e.g. Powershare/outlet fields only exist on supporting vehicles); absent
  fields mean "no data", not zero.
