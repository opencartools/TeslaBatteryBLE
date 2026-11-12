# Infotainment polling and vehicle sleep

Evidence behind ADR-0009. Competitive research lives here only (AGENTS.md).
Sources were read on 2026-10-08; quotes are short extracts.

## What other projects found

- **VCSEC status does not affect sleep.** Tesla's Go SDK says a VCSEC-only
  client "can avoid waking infotainment"
  (https://pkg.go.dev/github.com/teslamotors/vehicle-command/pkg/vehicle).
  The esphome-tesla-ble README: "VCSEC status polling is low-power and does not
  affect vehicle sleep" (https://github.com/yoziru/esphome-tesla-ble).
- **Infotainment polling keeps the car awake.** esphome-tesla-ble PR #198:
  "Infotainment polling with WAKE_IF_NEEDED every 30s kept the car awake,
  preventing VCSEC from ever reporting ASLEEP"
  (https://github.com/yoziru/esphome-tesla-ble/pull/198).
- **Their back-off, PR #213** (https://github.com/yoziru/esphome-tesla-ble/pull/213),
  read from the code (`TeslaBLEVehicle::update`):
  - VCSEC every 10 s, always.
  - "Active" is charging or Sentry Mode only. Unlocked and user present were
    removed, because "a car that is merely left unlocked, or reports user
    presence because a phone is in range, can still fall asleep on its own"
    (their issues #201 and #202).
  - Infotainment every 10 s while active, every 30 s while awake and idle for
    the first 660 s after the last activity or a (re)connect, then every 660 s
    without waking the car.
  - "A car that is merely observed asleep, or briefly blips awake on its own,
    must not restart the aggressive polling window or it would be re-woken
    every time it tried to sleep."
- **Idle time before sleep.** esphome-tesla-ble defaults to 660 s ("default 11
  min"); tesla_ble_mqtt recommends at least 660 s
  (https://github.com/iainbullock/tesla_ble_mqtt_docker). TeslaMate's FAQ: the
  car "does not fall asleep before it have been inactive for some 15 minutes"
  (cloud API, not BLE;
  https://github.com/teslamate-org/teslamate/blob/main/website/docs/faq.md).
- **Idle links.** TeslaBleHttpProxy: cars "may terminate connections after ~30
  seconds" (https://github.com/wimaha/TeslaBleHttpProxy#connection-timeouts).
  Our 10 s VCSEC status poll keeps the link busy.

## Where ADR-0009 differs, and why

- Any Infotainment request restarts the car's own sleep countdown (about 10
  minutes; the project owner's premise, 2026-10-08; measured shorter on
  2026-10-09, see "Real-car findings"). A 30 s window for 11
  minutes would keep the car awake about 21 minutes instead of 10, and 660 s
  idle reads could keep it awake indefinitely. So ADR-0009 drops both: 10 s
  while charging or driving, and for a short hold (1 min, or 10 min when the
  car reports someone present) after each status change, then nothing.
- Driving counts as active because the phone rides along; the ESP32 bridge
  stays home and never sees a drive.
- The app's own reads never wake the car: a wake is only ever the user's pull,
  so an unprompted wake-up blip only starts the short hold.

## Presence

- The owner's experience (2026-10-09): VCSEC reports a user present when
  someone sits in the car; approaching with a phone key does not set it. This
  differs from the PR #213 comment above, so the real-car check should confirm
  which applies.

## Real-car findings (2026-10-09)

Owner test of the build before the Infotainment session gate fix, one car, about a day of
logcat plus the status log. No VIN, car ID, BLE name or raw log is recorded here.

### Link quality

- The phone sat at the edge of BLE range inside the house: about 650
  connect/disconnect cycles in a day. Link up median 15 s, down median 20 s.
- Every drop happened at RSSI below -95 dBm. None happened near the car or in
  it.
- RSSI by position:

  | Position           | RSSI (dBm)    |
  | ------------------ | ------------- |
  | In the house       | -104 to -110  |
  | Approaching        | about -73 to -88 |
  | In the car/driving | -40 to -55    |

### VCSEC user presence

- Readings with presence true: median -49 dBm. Presence false: median -107 dBm.
- It turned on with a door open at about -82 dBm: someone getting in, not just
  a phone key approaching.

### Readings

- `battery_range` moves in steps of 0.0816 mi (multiples seen: 0.1633,
  0.2449, ...).

### Sleep

- With the old build reading every ~15 s, the car still fell asleep about 18
  minutes after a wake (30 s after the last read), and 2-5 minutes after the
  last lock or door change.
- Caveat from the owner: the phone was walking away and the link dropping, so
  this does not yet prove reads do not hold the car awake. A test with a stable
  link is planned.

### Wakes

- Unexplained wakes at 02:15 and 03:32 UTC were likely the owner debugging, not
  the app. The logcat buffer did not cover them, so they stay unattributed.
- Auto-start after an app update worked (`MY_PACKAGE_REPLACED` in logcat).

### Bug found: reads blocked for the whole morning

The log held VCSEC status from the wake at 08:34:45 through unlock, doors and a
20-minute drive, but no charge or drive read. Replaying the statuses through
`InfotainmentPollPolicy` showed it asked for reads (due at the wake and
repeatedly during the drive), so the controller blocked them. The controller
passed `sessionReady = !chargeAfterSession && ...` to the policy; the flag was
set when a read started the Infotainment handshake and cleared only when the
session was established or the link closed. When the handshake never finished
(retries ran out, the car went back to sleep, or the link dropped) the flag
stayed set, the policy never got `sessionReady`, and no new handshake started.
Fixed by `InfotainmentSessionGate`; see ADR-0009.

### 2026-10-09 afternoon

Follow-up tests with a stable link, which settle the caveat in "Sleep" above.

- **Reads keep the car awake.** An old build reading every ~5 s held it awake
  for 22 minutes (13:50 to 14:12 UTC). The car slept 2.5 minutes after the
  reads stopped, in line with ADR-0009 (reads only on activity or in a hold).
  That figure comes from logcat on the day and is not reproducible: the status
  log for that window, read later, shows `presence=NOT_PRESENT` throughout, the
  car asleep before the run, and no status record at all between 14:05:35 and
  14:27:41 UTC, so it only brackets the sleep to those 22 minutes. See the
  2026-11-12 entry for the tails that can be replayed.
- **A second test:** the car slept 30 s after the last read, and 7.5 minutes
  after a wake.
- **Two apps on one phone:** with a second app running, the newer app's reads
  mostly failed. The suspects are interleaved BLE writes or notifications from
  the two apps. Run one app at a time.
- **At wake the closures briefly read UNKNOWN.** The poll policy ignores that
  correctly: UNKNOWN does not count as open, so it starts no hold.
- **We could not tell the app's requests from the owner's.** Neither a wake nor
  a request in these logs could be attributed to the app or to the owner, nor
  could we tell whether the app was in the background. That led to the command
  and app-state logs (`<vehicleId>.command.pblog` and `app.pblog`, ADR-0008).
- **A remote wake alone is short.** The owner woke the car over BLE, took one
  read and nothing more: it slept after about 2 minutes. It stays awake longer
  only while reads continue or someone physically uses the car, so the car's
  own countdown after a BLE wake is far shorter than the ~10 minutes ADR-0009
  assumed for a read.

### 2026-11-10 and 2026-11-12: how long a gap lets it sleep

Two runs on the same car at home, same spot, phone indoors at -101 to -106 dBm,
with nobody near the car: VCSEC reported "User away" and no door, lock or
presence change happened during either run, so the sleep these runs measure is
the one that follows a BLE request, not a physical interaction.

- **Continuous reads hold it.** One `USER_WAKE`, VCSEC `AWAKE` 0.4 s later, then
  131 charge reads over 22 min 30 s, about one every 9 s (the 10 s read interval
  around a pull every ~13 s). 24 of the 131 requests timed out (18%) and the
  reads still landed. The car stayed awake for the whole 20 minutes of pulling;
  the pill showed `Paused` 43 s after the last read and `Asleep` at 92 s.
- **One read a minute holds it.** A debug build with the hold set to zero, so
  each pull is exactly one read: ten pulls 60 s apart (gaps 58.8-61.3 s) kept
  the car awake for all 9 minutes. The command log holds one `USER_WAKE` in the
  whole window, and a sleeping car needs a wake before it answers, so no gap let
  it sleep. One of about 30 follow-ups timed out and the sequence was unbroken.
- **A 90 s gap ends it.** A successful read at 13:36:28.5 was followed by VCSEC
  `ASLEEP` at 13:37:58.2, 89.7 s later. A later wake, whose charge read never
  landed, slept again about 80 s after the last exchange, before the next pull.

So the car sleeps about 1.5 minutes after the last BLE request, and stays awake
while requests keep coming at least once a minute: a minute or less between
reads keeps a parked car awake indefinitely, and a 90 s gap lets it sleep. That
narrows both the
"0.5-2.5 minutes past the last read" reading in ADR-0009 and the "about
2 minutes" one above. The 2.5 minute tail itself does not reproduce and cannot
be classified: that build logged no commands and kept no charge samples until
2026-10-10 03:42 UTC, so its reads survive only as the note above, while its
status log shows nobody in the car. Whether the tail was detection lag or
another client on the link (the official app is a phone key on the same
connection) cannot be told.

## Related

- Issue #112 (the report), `docs/protocol/capabilities.md`
  (Constraints: Polling).
