# ADR-0001: Tesla BLE battery tracker

Status: Accepted
Date: 2026-10-03

Android app (iOS later) that talks to a Tesla over BLE, starting with battery
SOC. No cloud. Kotlin + Compose with a Kotlin protocol core; no FFI.

## Protocol facts (verified against teslamotors/vehicle-command)

- GATT: service `00000211-b2d1-43f0-9b88-960cebf8b91e`, write `...0212...`,
  notify `...0213...`. `FEE7`/`7430000x` are not this protocol.
- Framing: 2-byte big-endian length, `MTU - 3` chunks, MTU 256.
- `UniversalMessage.RoutableMessage`, per-domain sessions: VCSEC (lock, closures;
  pollable while asleep) and Infotainment (charge state; requires awake car).
- Auth: P-256 ECDH session, AES-GCM (12-byte nonce on BLE) or HMAC-SHA256.
  Sessions are kept in memory and re-established by handshake; see Correction.
- Advertised name: `S` + `sha1(vin)[0..8]` hex + `C`. Advertising stops when the
  car is at its maximum number of BLE connections.
- Pairing: unsigned `addKey`, NFC card tap on the console plus vehicle confirm.
  Roles: OWNER, DRIVER, CHARGING_MANAGER. Form factor ANDROID_DEVICE.

## Decisions

- Port the protocol to Kotlin from the Apache-2.0 Go implementation and the MIT
  Swift ports; reuse Tesla `.proto` files via Wire. The AGPL C++ libraries
  (pmdroid, yoziru/PedroKTFC lineage) are behavioral references only, never
  copied. Rejected: Go via gomobile (FFI, toolchain, key leaves Keystore),
  Capacitor/Flutter/RN (no protocol library), Python on device.
- Scanning: `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` + `ACCESS_FINE_LOCATION` on
  all API levels. Rejected the `neverForLocation` shortcut: the platform may
  filter scan results with it, and requesting location is the compatible
  behavior on every Android version, including 16.
  Amended 2026-10-29: the flag is out again. With it declared, both a Jelly
  Star on Android 13 and a Samsung on Android 16 found no car while scanning,
  yet still reached their paired car at its known address; a Jelly Star scan
  heard 742 advertisements, 323 of them named, and not one Tesla. Android drops
  beacon-style advertisements for apps that disavow location, and the cars
  advertise that way. Without the flag a scan needs fine location on Android 12+
  as well. It is not part of onboarding: a car at its stored address reconnects
  without a scan, so location is asked when a scan needs it (the first run, a
  pull to scan, or a reconnect that must rediscover a car at a new address),
  and the cars list shows a card when discovery is blocked.
  When Android will not show the location dialog again, a scan opens the system
  companion-device chooser instead (no location permission; Location Services
  still on), which adds the car once. It creates an OS association per car and
  does not help rediscovery after an address rotation, which still needs a scan.
- Default pairing role: CHARGING_MANAGER. Driver/owner only by explicit opt-in.
- Polling: VCSEC ~10s (safe while asleep); Infotainment charge state only while
  the car is awake, ~10s while charging. Never wake the car to read SOC.
- Key storage: software P-256 encrypted by a Keystore AES key; TEE-backed ECDH
  later.

## Milestones

1. DONE: scan by name pattern, connect, service discovery, MTU, GATT name.
2. Framing + session handshake, with JVM tests ported from Go vectors.
3. Pairing: unsigned addKey.
4. `BodyControllerState` + `GetState(Charge)` -> SOC on screen.
5. Foreground service, polling. (Encrypted session persistence was dropped: sessions live in memory and re-handshake.)
6. Hardening, then the KMP/iOS decision.

## Risks

- Crypto edge cases: 12-byte nonce AES-GCM, SHA-1-truncated KDF, metadata hash,
  counters, clock sync. Pin the proto commit; use `tesla-control` as an oracle.
- Android OEM battery management kills background BLE; treat collection as
  best-effort, foreground service when needed.

## Correction (2026-10-07)

The original "4-byte nonce" was wrong (found in the docs audit, #128): the BLE
protocol uses standard 12-byte AES-GCM nonces (Go takes the size from
`gcm.NonceSize()`, `TeslaCrypto.NONCE_SIZE` is 12), and the text above now says so.
Session persistence (milestone 5) was never built; sessions are in memory only.

## References

- https://github.com/teslamotors/vehicle-command
- https://github.com/misakatao/TeslaBLEKeyKit
- https://github.com/shoujiaxin/swift-tesla-ble
- https://github.com/JuulLabs/kable
- https://github.com/square/wire
