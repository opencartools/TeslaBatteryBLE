# Pulling the history logs off the phone

Since ADR-0014 the raw history lives in `databases/history.db` (Room), not in `files/battery-history/*.pblog`.
That directory now holds only `*.imported` files, the old logs renamed after their one-time import. A
`tar` or `cat` of it returns stale data. To get current logs, use the export below.

## Framed `.pblog` files (the format the decode tooling reads)

The app writes one framed `<vehicleId>.<kind>.pblog` per series (and `app.pblog`) into a zip, the same
bytes the old files held. Only a debuggable build does it: the CI debug builds (`com.dzid26.teslable.pr`)
and the rolling "Preview of main" debuggable APK (`com.dzid26.teslable`). A release build ignores the
broadcast. Replace the package name for the build you pull from.

```bash
adb shell am broadcast -a com.dzid26.teslable.EXPORT_LOGS -n com.dzid26.teslable/com.dzid26.teslable.history.ExportLogsReceiver
adb exec-out run-as com.dzid26.teslable sh -c 'cat files/battery-history-export/$(ls files/battery-history-export | sort | tail -n 1)' > history-export.zip
```

For the PR build use `-n com.dzid26.teslable.pr/com.dzid26.teslable.history.ExportLogsReceiver` and
`run-as com.dzid26.teslable.pr`. Check `adb logcat -s ExportLogs` for "exported <time>.zip" if the zip is
missing. The newest three exports stay in `files/battery-history-export/`. Unzip, then decode each file
with `ProtoLog.decode(bytes, BleRecord.ADAPTER)` as before (`docs/adr/0006-raw-chargestate-history-log.md`).

`vehicleId` empty (rows from before multi-car) is exported as `legacy.<kind>.pblog`; the app-state log is
`app.pblog`.

## The database itself

For queries instead of decoding, copy the database with its write-ahead log, or the newest rows are
missing:

```bash
adb exec-out run-as com.dzid26.teslable sh -c 'cd databases && tar -cf - history.db history.db-wal history.db-shm' > history-db.tar
```

Open the extracted `history.db` with the `sqlite3` shell. `records` holds the car's replies (`blob` is the
encoded `BleRecord`), `events` the phone-side commands, connection changes and app state, `series` and
`origins` give the integer ids their vehicle, kind and install. Copying while the app writes can catch a
torn state; prefer the export for anything you need to trust.

A `history.db.corrupt-<time>` file next to it is a database that failed to open and was kept aside; the
app never deletes it.
