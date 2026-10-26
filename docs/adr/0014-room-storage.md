# ADR-0014: Room storage for raw records and derived data

Status: Proposed (raw records in `history.db` landed; `owner.db` and `derived.db` are open)
Date: 2026-10-21
Related: ADR-0002, ADR-0004, ADR-0006, ADR-0007, ADR-0008, ADR-0012

## Context

- Raw `BleRecord`s are stored as append-only `.pblog` files, one per vehicle and
  kind (ADR-0006, ADR-0008). Each file keeps its newest 20,000 records. At the
  cap, `CappedLog` decodes the whole file and rewrites it every 1,000 appends.
  ADR-0012 raises the backstop to 200,000 records and keeps raw data by age, so
  that rewrite would cost tens of MB each time.
- ADR-0012 also needs per-session and per-day summaries, written before raw
  records are dropped. With files, "write the summary, then delete the raw data"
  is two ordered steps across two stores.
- ADR-0006 and ADR-0012 left the storage engine open and named Room with raw
  record bytes in a BLOB column as the way out.
- Owner-entered data (energy prices, places) is the only data that cannot be
  rebuilt, and today it lives in SharedPreferences.
- Two phones can poll the same car and later be merged, and a TeslaMate sync
  needs to know which records are already sent.
- ADR-0007: only `:protocol` and `:core` become KMP. Room stays in `app`.

## Decision

- **Raw records move into Room** (Android only, in `app`) as opaque blobs with
  typed key columns, in a database `history.db`:
  `(id, vehicleId, kind, carTimestamp, phoneTime, originId, odometer, blob)`.
  - `blob` is the encoded `BleRecord`, so unknown and future Tesla fields
    survive untouched, as in ADR-0006. The schema never holds proto fields.
  - `carTimestamp` is the car's own timestamp and is the timeline (ADR-0006,
    ADR-0008). It is nullable: VCSEC status and some other replies carry none,
    and a charge or drive reply may lack it (ADR-0008). Such rows are timed by
    `phoneTime`, and the unique key uses `coalesce(carTimestamp, phoneTime)`. `phoneTime` is the acquisition time from the envelope. `originId`
    is a random per-install id.
  - `odometer` is nullable: filled at insert from records that carry it, so log
    pulls and trip lookups by distance need no blob decoding. It is a plain
    column, not part of the unique key.
  - Indexes on `(vehicleId, kind, carTimestamp)` and `(vehicleId, odometer)`. Unique key on
    `(vehicleId, kind, carTimestamp, originId)`, so a re-import is a no-op.
  - Writes are batched in short transactions (WAL, `synchronous=NORMAL`).
- **Retention is a `DELETE`** by age (ADR-0012's 90 days and 200,000-row
  backstop), run in the same transaction that writes the summaries it folds. A
  record is never deleted before its summary is committed. (Age retention is off until the summaries exist;
  see the implementation notes.)
- **Two more databases, as in the charging plan.**
  - `owner.db` (backed up): prices and places. Schema export is binding from the
    first normal release.
  - `derived.db` (not backed up, versioned): session and day summaries per
    ADR-0012, stored as plain columns. Rebuildable from raw rows for as long as
    they exist; older rows are the only copy once raw ages out.
  Raw `history.db` is also excluded from backup (ADR-0006).
- **Merging phones is thinning, not dedup.** Two phones polling the same car
  give a denser stream, not duplicate rows. Merge by car timestamp, keep
  the `originId`, and optionally thin to one record per N seconds. Derived
  results do not depend on thinning. TeslaMate sync keeps a "synced up to" mark
  per origin.
- **Tooling and export.** An in-app "Export logs" routine writes framed
  `<vehicleId>.<kind>.pblog` files (same `ProtoLog` encoding) into a zip, so the
  decode tooling and the existing log pulls keep working on exports. A debuggable
  build may also be pulled with `run-as` (database plus `-wal` and `-shm`); the
  host decodes it with a small JVM tool.
- **Core stays free of Room.** Core exposes plain store interfaces; the app
  implements them. `HistoryStore`'s public API stays stable while storage moves.
- **Migration.** Existing `.pblog` files are imported once at first start under
  the new version (idempotent through the unique key), then renamed. ADR-0004's
  no-migration rule allows dropping them instead; importing keeps the owner's
  current real-car logs.

## Implementation notes (raw records)

- `carTimestamp` is the car's own `ChargeState`/`DriveState` timestamp and null for every other reply,
  which `phoneTime` times. `odometer` (hundredths of a mile) comes from drive replies. Room has no
  annotation for an expression index, so a database callback creates the unique key on
  `(vehicleId, kind, coalesce(carTimestamp, phoneTime), originId)`; the entity declares the other
  indexes, and one on `phoneTime` serves age retention. The app's own state has an empty vehicle id and
  kind `app`. Two records of one series in the same millisecond from one origin collapse to one.
- There is no age retention for now (owner decision 2026-10-26: keep years, so the stats that read the
  whole log, such as sleep and rest, the range-scale fit and the health card, keep seeing it). Age
  retention returns together with the derived store and its summaries (ADR-0012). Until then a safety
  cap on size runs every 1,000 appends: past 2 GB of rows (blob plus 200 bytes of key and index each),
  the oldest rows of the largest series are deleted, down to 90% of the cap, in one transaction.
  Measured on 116,353 real rows (2026-10-26, 14 MB of `.pblog` files from the phone): 13.9 MB of blobs
  (about 119 bytes a row) in a 36.0 MB `history.db` (no WAL left after close), so 190 bytes a row of key
  columns and indexes, about 309 bytes a row. At the current polling that is about 12,800 rows
  (3.9 MB) a day while the command log is chatty (6,900 rows a day over its last 2.8 days) and about
  6,900 rows (2.1 MB) a day otherwise, so 2 GB holds roughly 1.4 years at the first rate and 2.6 years
  at the second.
- The first start under this version imports `<vehicleId>.<kind>.pblog` and `app.pblog` through the unique
  key and renames them to `*.imported`. `history.db` and its `-wal`/`-shm` are excluded from backup;
  the origin id lives in the no-backup directory.
- `HistoryStore.exportLogs` writes the zip. A database that fails to open (damaged, or a schema
  without a migration) is never deleted: `history.db` and its `-wal`/`-shm` are renamed to
  `history.db.corrupt-<time>`, a new database is created, and failing that the history runs in memory.
  Imported `.pblog` files stay on disk as `*.imported`. `fallbackToDestructiveMigration` is not used;
  real Room migrations are required from the first normal release (ADR-0004: pre-1.0 formats change
  freely, and the old file is kept for the owner).
- Records of kinds timed only by the phone clock (command, connection, app) that fall in the same
  millisecond collapse to one row through the unique key. The replay lost 55 of 116,353 rows that way
  (31 with distinct content); kinds with a car timestamp lost none and the derived chart, drive and
  status samples came out identical.

## Consequences

- The trim rewrite disappears; retention and "summarise then drop" are one
  transaction.
- Time-range reads are indexed; the live chart reads only its range, and startup
  stops decoding whole files.
- Log pulls need the export routine or a host tool, not just `tar`. Release
  builds only have the export routine.
- Size on disk grows by roughly a fifth to two fifths over the files (row and
  index overhead, WAL). Measure on real logs before the backstop is final.
- The raw layer stays Android-only. A future iOS app implements the same store
  interfaces with its own storage.
- Room adds KSP, APK size and CI time. The first schema export after the first
  normal release is binding for all three databases, so the key columns are
  chosen deliberately now.
- A damaged `history.db` could lose more than one tail record. Room's integrity
  checks and rebuild-from-export cover that; the app must not crash on open
  failure and falls back to an empty database.

## Alternatives considered

- **Files only, segmented by time.** Retention by deleting old segments; no
  rewrites; tooling unchanged. Rejected by the owner: it keeps a second storage
  mechanism beside Room, has no indexed reads, and makes merge and sync
  bookkeeping manual.
- **Files plus a Room index.** Two sources of truth that can disagree after a
  crash or trim.
- **Typed rows, one column per proto field.** Every new Tesla field would need a
  migration, which the schema-free blob avoids.
