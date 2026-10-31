# ADR-0003: Fake car behind a transport seam

Status: Accepted
Date: 2026-10-04

## Context

Validating UI and protocol flows required a real car and phone: slow,
non-deterministic, and disruptive to the owner. The controller was also wired
directly to Android GATT, so no flow could run in an emulator or in JVM tests.

## Decision

- `TeslaTransport` (connect/send/close/readRssi plus a listener) is the seam
  between the controller and the wire. `TeslaGattClient` implements it for real
  cars; the controller never touches `BluetoothGatt` directly.
- `FakeCarProtocol` is a pure-JVM implementation of the car side: VCSEC status
  and whitelist replies, the add-key pairing flow with a simulated card tap,
  session handshakes, and encrypted wake/charge responses. `FakeTeslaTransport`
  adds scheduling and RSSI jitter around it.
- The fake lives in the debug source set. A debug build starts it with
  `-PdemoCar=true` or turns it on from the Debug settings switch; release builds
  never contain it and always use GATT.
- The switch is additive: enabling it adds the simulated car to the next scan
  next to the real ones, and disabling it closes only the fake car's link.
- CI and local runs share `.github/scripts/capture-screenshots.sh`; the demo
  steps simply skip when the app is a real build.

## Consequences

- UI and protocol flows can be exercised on any emulator, and the fake is
  covered by JVM round-trip tests that drive it with the real client code.
- The fake is a test double, not a conformance oracle: it does not enforce
  expiry, epochs, or replay windows the way the vehicle firmware does.
