# Protocol notes

How TeslaBatteryBLE talks to a Tesla over Bluetooth LE. Implemented in
[`protocol/`](../../protocol) and wired to Android BLE in [`app/`](../../app). Verified
against Tesla's [`vehicle-command`](https://github.com/teslamotors/vehicle-command),
through our fork [`opencartools/vehicle-command`](https://github.com/opencartools/vehicle-command)
that adds the fields the car sends and Tesla's protos lack (the proto revision is
pinned in [`protocol/src/main/proto/TESLA_COMMIT`](../../protocol/src/main/proto/TESLA_COMMIT)).

To bump the protos, rebase the fork's `main` on `teslamotors/main`, merge the change
there, write the new commit hash to `TESLA_COMMIT`, copy all nine `.proto` files from
`pkg/protocol/protobuf` at that commit, then regenerate
`docs/reference/fleet-telemetry-vs-ble.md` with `tools/signal-matrix/generate.py`.
If the Go implementation changed, regenerate `tools/go-fixtures/expected.txt` too.
CI's go-vectors job diffs the copies against the pin, and the generated-doc check
fails if the matrix is stale.

## Transport

| Item | Value |
| --- | --- |
| GATT service | `00000211-b2d1-43f0-9b88-960cebf8b91e` |
| Write characteristic | `...-00000212-...` |
| Notify characteristic | `...-00000213-...` |
| MTU | 256 (negotiated; usable payload `MTU - 3`) |
| Framing | 2-byte big-endian length prefix, then chunks of `MTU - 3` |

GATT writes are serialized (one request in flight at a time), and responses arrive
on the notify characteristic. The protocol is strictly **request/response — there is
no subscription or server-initiated push over BLE**. See
[`BleFramer.kt`](../../protocol/src/main/kotlin/com/dzid26/teslable/protocol/framing/BleFramer.kt)
and [`TeslaGattClient.kt`](../../app/src/main/java/com/dzid26/teslable/ble/TeslaGattClient.kt).

Scanning: Teslas advertise a name of the form `S<8 hex chars of sha1(vin)>C`.
Advertising stops when the car has reached its maximum number of BLE connections, so
"not visible" can mean "slots full", not "out of range".

## Messages and domains

Messages are `UniversalMessage.RoutableMessage` protobufs, routed to one of two
domains:

| Domain | Scope | Works while car is asleep? |
| --- | --- | --- |
| **VCSEC** (`DOMAIN_VEHICLE_SECURITY`) | Lock state, closures, presence, whitelist/key info, wake | **Yes** — this is the always-available channel |
| **Infotainment** (`DOMAIN_INFOTAINMENT`) | Charge state (SOC, range, charger power), climate, drive state, media, software update | **No** — requires an awake car |

Key facts:

- `GetVehicleData` returns **one category per request** (charge, climate, drive, …).
- Commands are role-gated car-side: `CHARGING_MANAGER` is enough for charge reads and
  charge control; locks/climate need `DRIVER`; key management needs `OWNER`.
- Enrollment uses the unsigned `addKey` flow: the car must be in pairing mode (NFC
  card tap on the console), then the vehicle confirms. Default role here is
  `CHARGING_MANAGER` (least privilege); other roles are explicit opt-in.
- Polling policy: VCSEC ~10 s while tracking; Infotainment only while the car is
  awake (~10 s while charging). **Never wake the car just to read SOC.**

## Sessions and pairing

See [`session-and-pairing.md`](session-and-pairing.md) for the handshake, crypto, and
key-storage details.

## Testing the protocol without a car

- **Go fixtures**: `tools/go-fixtures` generates deterministic vectors from Tesla's Go
  implementation; CI diffs them against `expected.txt`.
- **Simulated car**: debug builds include a fake transport/protocol so the full
  scan → pair → read flow runs on the JVM and on emulators.

## See also

- [`../reference/fleet-telemetry-vs-ble.md`](../reference/fleet-telemetry-vs-ble.md) —
  generated cloud-vs-BLE signal matrix: every Fleet Telemetry field and whether
  it is readable over BLE.
- [`capabilities.md`](capabilities.md) — what the car exposes over BLE: readable
  data, commands, what is not available, and the wake, role and polling constraints.
