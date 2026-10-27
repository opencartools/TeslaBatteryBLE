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
  typed key columns, in a database `history.db`. The text that repeats in every row (vehicle id, kind, the
  install's UUID) sits in two small lookup tables, `series(id, vehicleId, kind)` and `origins(id, uuid)`,
  and rows hold their integer ids; this halves the database against text columns and indexes.
  - `records` (the car's replies): `(id, seriesId, originId, carTimestamp, phoneTime, odometer, blob)`.
  - `events` (the phone's own logs, below): `(id, seriesId, originId, phoneTime, contentHash, blob)`.
  - `blob` is the encoded `BleRecord`, so unknown and future Tesla fields
    survive untouched, as in ADR-0006. The schema never holds proto fields.
  - `carTimestamp` is the car's own timestamp and is the timeline (ADR-0006,
    ADR-0008). It is nullable: VCSEC status and some other replies carry none,
    and a charge or drive reply may lack it (ADR-0008). Such rows are timed by
    `phoneTime`, the acquisition time from the envelope. `originId` is the random per-install
    id (its UUID is in `origins`).
  - `odometer` is nullable: filled at insert from records that carry it, so log
    pulls and trip lookups by distance need no blob decoding. It is a plain
    column, not part of the unique key. The column stays, and keeps being filled, but it has no
    index until a reader needs one (owner decision 2026-10-27): adding it later is a `CREATE INDEX`
    migration with no change to the data.
  - `records` has one index, the unique key `(seriesId, coalesce(carTimestamp, phoneTime), originId)`,
    so a re-import is a no-op (it also serves the newest-first reads, which order by the same
    expression). Room cannot declare an expression index, so a database callback creates it.
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

- `carTimestamp` is the car's own `ChargeState`/`DriveState` timestamp and null for every other reply.
  The app's own state has an empty vehicle id and kind `app`. Two records of one car series in the
  same millisecond from one origin collapse to one row; the real logs have none (charge and drive
  timestamps are distinct), so the key drops nothing there.
- **What this change delivers, and what it does not yet.** It delivers storage, indexing, dedupe,
  retention and crash safety. The read models still load only the newest `MAX_SAMPLES` (20,000, and
  50,000 for the activity samples) of each kind at start, as the files did: about 15 days of charge and
  drive records (the command log, at about 6,500 records a day, covers about 3). Rows beyond that window
  are stored, not lost, and wait for the readers that use them: time-range queries for the chart and
  the stats, then `derived.db`. Startup still decodes up to 20,000 blobs a kind, now through an indexed
  query instead of a file scan.
- There is no age retention for car records (owner decision 2026-10-26: keep years). Age retention
  returns together with the derived store and its summaries (ADR-0012). Until then a safety cap on size
  holds: past 2 GB of rows (blob plus 64 bytes of key and index each) the oldest rows of the largest
  series are deleted, down to 90% of the cap, in one transaction. The check runs every 1,000 appends
  and starts with the pages in use (`page_count - freelist_count`, times `page_size`): the per-series
  `SUM(length(blob))` scan runs only once those reach the cap, and a trim frees pages, so the check falls
  back below the cap afterwards.
- **Measured on R's real logs** (2026-10-26, 14 MB of `.pblog` files from the phone, 116,384 rows after
  dropping 24 exact duplicates): 13.9 MB of blobs (about 119 bytes a row) in a 20.4 MB `history.db`, so
  about 56 bytes a row of keys and indexes and about 175 a row; roughly 1.45 times the files. The first
  layout, with the text columns repeated and three indexes on `records`, took 35.7 MB (2.6 times the
  files, about 309 a row). At the current polling, about 12,800 rows (2.2 MB) a day while the command log
  is chatty (6,900 rows a day over its last 2.8 days) and about 6,900 rows (1.2 MB) a day otherwise,
  2 GB holds roughly 2.5 years at the first rate and 4.6 years at the second.
- **Trim timing** on the same data: the gated `trim()` takes about 5 ms on the real database; the per-series
  size scan, which now runs only near the cap, takes 80 to 110 ms there, and 2.7 to 6.9 s on ten times
  the data (1.16 million rows, 205 MB), so tens of seconds at the cap, once per roughly 10% of growth.
  Deleting nearly all of those 1.16 million rows took 8 to 40 s.
- The phone-side logs (command, connection and app state) live in the `events` table. They follow no
  car timestamp and several can share a millisecond, which the car table's key would have collapsed
  (the first replay lost 55 of 116,408 rows that way, 31 of distinct content: a connected/disconnected
  pair, tracking-started and screen-on, two command results with different reasons). Their unique key is
  `(seriesId, originId, phoneTime, contentHash)`, where `contentHash` is the first 64 bits of the
  blob's SHA-256, so only an exact duplicate is ignored. The series, which includes the vehicle, is part
  of the key so that two cars' identical events stay apart; `vehicleId` is empty for app events.
  Events are kept for 90 days (owner decision 2026-10-26), deleted by `phoneTime` with the other
  retention at start and every 1,000 appends. There is no `phoneTime` index: that `DELETE` scans the
  events table, which is cheap at its share of the cap. Events also get 15% of the size cap as a
  backstop; the car records get the rest. Each is trimmed oldest-first within itself, so a chatty command
  log cannot evict car history. Import routes the command, connection and app `.pblog` files into
  `events`, and the export writes them back as the same framed files. Replay of the same logs: every
  file matches its original record for record, except 24 exact duplicate command records that were
  stored twice in the old file.
- The first start under this version imports `<vehicleId>.<kind>.pblog` and `app.pblog` through the unique
  key and renames them to `*.imported`. `history.db` and its `-wal`/`-shm` are excluded from backup;
  the origin id lives in the no-backup directory.
- **Getting logs out.** `HistoryStore.exportLogs` writes the zip. For a debuggable build, a shell broadcast
  to `ExportLogsReceiver` writes it to `files/battery-history-export/<time>.zip`, where `run-as` can read
  it; the receiver ignores a release build and only the shell holds the permission it asks for. There is
  no in-app button yet. The pull commands, and how to copy the database itself, are in
  [`docs/reference/log-pull.md`](../reference/log-pull.md). The old `run-as ... tar files/battery-history`
  now returns only the stale `*.imported` files.
- A database that fails to open (damaged, or a schema without a migration) is never deleted:
  `history.db` and its `-wal`/`-shm` are renamed to `history.db.corrupt-<time>`, a new database is
  created, and failing that the history runs in memory, with one log line, so writes are lost until
  restart. Imported `.pblog` files stay on disk as `*.imported`. `fallbackToDestructiveMigration` is not
  used; real Room migrations are required from the first normal release (ADR-0004: pre-1.0 formats
  change freely, and the old file is kept for the owner).

## Consequences

- The trim rewrite disappears; retention and "summarise then drop" are one
  transaction.
- Time-range reads are indexed, so the chart and the stats can read only their range and startup
  can stop decoding a fixed window of blobs. Today's read models do not use that yet (see the
  implementation notes).
- Log pulls need the export routine or a host tool, not just `tar`: the debug broadcast in
  `docs/reference/log-pull.md`, or the database itself.
- Size on disk is about 1.45 times the `.pblog` files (20.4 MB for 14 MB of logs, about 175 bytes a
  row), measured on real logs.
- The raw layer stays Android-only. A future iOS app implements the same store
  interfaces with its own storage.
- Room adds KSP, APK size and CI time. The first schema export after the first
  normal release is binding for all three databases, so the key columns are
  chosen deliberately now.
- A damaged `history.db` could lose more than one tail record. Room's integrity
  checks and rebuild-from-export cover that; the app must not crash on open
  failure and falls back to an empty database, keeping the damaged file aside.

## Alternatives considered

- **Files only, segmented by time.** Retention by deleting old segments; no
  rewrites; tooling unchanged. Rejected by the owner: it keeps a second storage
  mechanism beside Room, has no indexed reads, and makes merge and sync
  bookkeeping manual.
- **Files plus a Room index.** Two sources of truth that can disagree after a
  crash or trim.
- **Typed rows, one column per proto field.** Every new Tesla field would need a
  migration, which the schema-free blob avoids.
