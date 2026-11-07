# Signal matrix (Fleet Telemetry vs BLE)

Generates [`docs/reference/fleet-telemetry-vs-ble.md`](../../docs/reference/fleet-telemetry-vs-ble.md):
a living comparison of what Tesla exposes through **Fleet Telemetry** (cloud, the same
vehicle data as the Fleet API JSON) versus what is readable over **local BLE**
(vehicle-command protobufs).

## Sources of truth

- **Cloud:** vendored protos from `teslamotors/fleet-telemetry`, pinned in
  `upstream/fleet-telemetry/FLEET_TELEMETRY_COMMIT`. The signal list comes from the
  `Field` enum in `vehicle_data.proto`.
- **BLE:** `protocol/src/main/proto/*.proto`, pinned to Tesla's vehicle-command revision in
  `protocol/src/main/proto/TESLA_COMMIT` (a commit of our fork, `opencartools/vehicle-command`).
- **The cross-map:** `mapping.json` — hand-curated equivalences, cloud-only rows, and the
  command table. This is the file to edit when you want the document to change.

## Commands

```sh
python tools/signal-matrix/generate.py            # regenerate the doc (offline, deterministic)
python tools/signal-matrix/generate.py --check    # fail if the committed doc is stale (CI)
python tools/signal-matrix/generate.py --refresh  # update vendored Fleet Telemetry protos from upstream
python tools/signal-matrix/generate.py --status   # print pinned, fork and upstream SHAs
python tools/signal-matrix/generate.py --dump     # list parsed message fields (mapping authoring)
python tools/signal-matrix/generate.py --import-docs <markdown>  # refresh Tesla's Available Data snapshot
```

No third-party Python packages are required.

## Types and fidelity

The generated doc labels every mapped row with the cloud logical type (from Tesla's
Available Data table) and the exact BLE proto type, and auto-lists where the same value
is typed differently (e.g. `BatteryLevel` is `real` on cloud with sub-percent values like
`40.982`, but BLE `battery_level` is `int32` whole percent; `charger_power` is `real` kW
on cloud vs `int32` whole kW on BLE). Precision annotations in the BLE proto comments
(`// 2 decimals`, `// 1 decimal`, `// seconds / datetime`) are extracted and shown in the
cross-map, the differences table, and the BLE catalog. It also documents Tesla's dynamic
wire format: legacy telemetry fields (< 179) usually arrive as string-encoded numbers,
field 179+ are always typed.

## Tesla's docs table (types + `vehicle_data` JSON equivalents)

`upstream/tesla-docs/available-data.tsv` is a snapshot of
[developer.tesla.com Available Data](https://developer.tesla.com/docs/fleet-api/fleet-telemetry/available-data):
Field, Category, Type, Fleet API `vehicle_data` JSON equivalent, Description.

The docs page is client-rendered, so refresh is a manual step:

1. Fetch the page (browser or `webfetch`) and save the rendered markdown.
2. `python tools/signal-matrix/generate.py --import-docs <saved-file>`
3. Regenerate and commit.

## Rules

- The generated doc is **not edited by hand**.
- Every `cloud` name in `mapping.json` must exist in the Fleet Telemetry `Field` enum and
  every `ble` reference must exist in the pinned BLE protos; the generator fails loudly
  otherwise (that is the CI `--check`).
- Signals in the Field enum that are neither mapped nor ignored show up under
  *Unreviewed upstream signals* in the doc. That table is the review queue; keep it empty.
- BLE protos are **never auto-bumped**: `protocol/src/main/proto` changes are deliberate,
  tested changes. The weekly workflow reports when the fork moves ahead of `TESLA_COMMIT`
  and prints Tesla upstream's latest proto commit as the next rebase target.

## Drift workflow

[`.github/workflows/signal-matrix.yml`](../../.github/workflows/signal-matrix.yml) runs
weekly (and on demand): it refreshes the vendored Fleet Telemetry protos, regenerates the
document, and opens a PR when anything changed. PRs touching this tool run `--check`.
