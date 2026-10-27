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

- **The car's raw records move into Room** (Android only, in `app`), minimal and opaque, in `history.db`:
  - `series(id, vehicleId, kind, clock)`, unique on `(vehicleId, kind)`. `clock` is `car` for the CarServer
    kinds (charge, drive, climate, closures, location, tire pressure), whose replies carry their own
    timestamp, and `phone` for VCSEC, whose replies carry none. It is defined on the kind and stored on the
    series row.
  - `origins(id, uuid)`, unique on `uuid`: the random per-install id, once, instead of in every row.
  - `records(id, seriesId, originId, time, odometer, blob)` with one index, declared in Room: unique on
    `(seriesId, time, originId)`.
  - `blob` is the encoded `BleRecord`, so unknown and future Tesla fields survive untouched, as in
    ADR-0006. The schema never holds proto fields.
  - `time` is epoch millis on the series' clock and is the timeline (ADR-0006, ADR-0008): the car's own
    timestamp for a car-clock series (read from every kind that stamps its reply), the phone's arrival
    time for VCSEC. A car-clock reply that lacks its timestamp is stored with the phone's time, never
    dropped. A re-import is a no-op through the unique key. Readers order by `time`, never by `id`.
  - `odometer` is nullable, filled at insert from drive replies. It is a plain column with no index until a
    reader needs one (owner decision 2026-10-27): adding the index later is a `CREATE INDEX` with no
    change to the data. The column stays so log pulls by distance need no blob decoding.
  - Writes are batched in short transactions (WAL, `synchronous=NORMAL`).
- **The phone's own logs live in a file of their own, `events.db`**: commands the app sent and the car's
  refusals, connection changes and the app's state. Same `series` and `origins` tables, and
  `events(id, seriesId, originId, phoneTime, contentHash, blob)`, unique on
  `(seriesId, phoneTime, originId, contentHash)`, where `contentHash` is the first 64 bits of the blob's
  SHA-256. Events carry no car timestamp and several can share a millisecond, so only an exact duplicate
  may be ignored. They are debugging logs of the phone, with a different life from the car's history.
- **Retention is a `DELETE`.** Car records have no age retention for now (owner decision 2026-10-26: keep
  years); it returns with the derived store (ADR-0012), in the same transaction that writes the summaries
  it folds, and no record is deleted before its summary is committed. Events are kept for 90 days (owner
  decision 2026-10-26). Each file also has a safety cap of 2 GB of pages in use
  (`(page_count - freelist_count) * page_size`): while over it, the rows older than a cutoff go, the oldest
  tenth of the file's time span, all series together, in one transaction.
- **Four files, as the charging plan needs them; two exist now.**
  - `history.db` (not backed up): the car's raw records, above.
  - `events.db` (not backed up): the phone's own logs, above.
  - `owner.db` (backed up, later): prices and places, the only data that cannot be rebuilt. Schema export is
    binding from the first normal release.
  - `derived.db` (not backed up, versioned, later): session and day summaries per ADR-0012, stored as plain
    columns. Rebuildable from the raw records only while they cover the period; once raw ages out, the
    summaries are the only copy.
  `owner.db` and `derived.db` reference raw records by `(vehicleId, time)`, never by row id, which is local
  to a file. The raw files are also excluded from backup (ADR-0006).
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

- **What this change delivers, and what it does not yet.** It delivers storage, indexing, dedupe, retention
  and crash safety. The read models still load only the newest `MAX_SAMPLES` (20,000, and 50,000 for the
  activity samples) of each kind at start, as the files did: about 15 days of charge and drive records (the
  command log, at about 6,500 records a day, covers about 3). Rows beyond that window are stored, not
  lost, and wait for the readers that use them: time-range queries for the chart and the stats, then
  `derived.db`. Startup still decodes up to 20,000 blobs a kind, now through an indexed query instead of a
  file scan.
- **Reader API.** `RecordStore` offers `append`, `readNewest(series, n)` and `readRange(series, from, to)`
  (both ordered by time, neither exposing a row id), and an internal `stream(series, afterId, limit)` for
  the exporters. `readRecent(kind, n)` is the per-kind convenience over them.
- **Size cap.** The check is two pragmas, so it costs nothing while there is room, and it runs outside
  the transaction because it reads over a separate connection (reading it inside deadlocks). Each pass
  deletes `time < first + span / 10` per series with an indexed range delete, at most 20 passes per call;
  a trim frees pages but the file keeps its length, which is why free pages are subtracted.
- **Measured on R's real logs** (2026-10-27, 14 MB of `.pblog` files from the phone, 116,384 rows after
  dropping 24 exact duplicate command records): `history.db` holds 84,275 car records in 17.2 MB and
  `events.db` 32,109 events in 2.7 MB, 19.9 MB together, about 1.4 times the files. Every file matches its
  original record for record. At the current polling, about 12,800 rows (2.2 MB) a day while the command
  log is chatty and about 6,900 (1.2 MB) otherwise; the events are bounded by their 90 days, so 2 GB of
  car history holds on the order of years.
- **Query plans** (SQLite `EXPLAIN QUERY PLAN` on that database). Every read is an index search with no
  sort: newest-first and range reads on `records` and on `events` search the unique key by series (and
  time range), the min and max times are covering-index lookups, the per-series `DELETE` by time is an
  index range, and the exporters' paging walks the primary key (`SEARCH ... USING INTEGER PRIMARY KEY
  (rowid>?)`, with a unary plus on `seriesId` so SQLite does not pick the series index and re-sort the
  series on every page). A cold single run read 20,000 charge records in about 1.2 s and all 19,786 of
  them by range in 0.8 s, nearly all of it decoding blobs; 20,000 command events took 0.15 s.
- The first start under this version imports `<vehicleId>.<kind>.pblog` and `app.pblog` through the unique
  keys, car kinds into `history.db` and command, connection and app into `events.db`, and renames them to
  `*.imported`. The databases and their `-wal`/`-shm` are excluded from backup; the origin id lives in the
  no-backup directory.
- **Getting logs out.** `HistoryStore.exportLogs` writes the zip: one framed `.pblog` per series and a
  `manifest.json` with the format version, the origin UUID, the vehicle ids, each file's record count and
  first and last time, and the app version. For a debuggable build, a shell broadcast to
  `ExportLogsReceiver` writes it to `files/battery-history-export/<time>.zip`, where `run-as` can read it;
  the receiver ignores a release build and only the shell holds the permission it asks for. There is no
  in-app button yet. The pull commands, and how to copy the databases themselves, are in
  [`docs/reference/log-pull.md`](../reference/log-pull.md). The old `run-as ... tar files/battery-history`
  now returns only the stale `*.imported` files.
- A database that fails to open (damaged, or a schema without a migration) is never deleted, in either
  file: it and its `-wal`/`-shm` are renamed to `<file>.corrupt-<time>`, a new one is created, and failing
  that the file runs in memory, with one log line, so its writes are lost until restart. Imported `.pblog`
  files stay on disk as `*.imported`. `fallbackToDestructiveMigration` is not used; real Room migrations
  are required from the first normal release (ADR-0004: pre-1.0 formats change freely, and the old file is
  kept for the owner).

## Consequences

- The trim rewrite disappears; retention and "summarise then drop" are one
  transaction.
- Time-range reads are indexed, so the chart and the stats can read only their range and startup
  can stop decoding a fixed window of blobs. Today's read models do not use that yet (see the
  implementation notes).
- Log pulls need the export routine or a host tool, not just `tar`: the debug broadcast in
  `docs/reference/log-pull.md`, or the database itself.
- Size on disk is about 1.4 times the `.pblog` files (19.9 MB across both files for 14 MB of logs, about
  170 bytes a row), measured on real logs.
- The raw layer stays Android-only. A future iOS app implements the same store
  interfaces with its own storage.
- Room adds KSP, APK size and CI time. The first schema export after the first
  normal release is binding for all four databases, so the key columns are
  chosen deliberately now.
- A damaged `history.db` or `events.db` could lose more than one tail record. Room's integrity
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
