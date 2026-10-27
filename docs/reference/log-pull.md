# Pulling the history logs off the phone

Since ADR-0014 the raw history lives in two Room databases, `databases/history.db` (the car's records) and `databases/events.db` (the phone's commands, connection changes and app state), not in `files/battery-history/*.pblog`.
That directory now holds only `*.imported` files, the old logs renamed after their one-time import. A
`tar` or `cat` of it returns stale data. To get current logs, use the export below.

## From the app (any build)

Settings has a **Logs** card with an **Export logs** button. It opens the system file picker (Storage Access
Framework), writes `teslabatteryble-logs-<UTC time>.zip` there in the background while the card shows
progress, and then says how many records it holds. It is the same zip as the debug pull below, so it works
on release builds, with no adb. The zip holds locations and drive details, so share it only where you mean to.

## Framed `.pblog` files (the format the decode tooling reads)

The app writes one framed `<vehicleId>.<kind>.pblog` per series (and `app.pblog`) into a zip, the same
bytes the old files held. From adb, only a debuggable build does it: the CI debug builds (`com.dzid26.teslable.pr`)
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

`manifest.json` in the zip says what it holds: the format version, the origin UUID of the phone, the vehicle ids, the app version, and for each file its record count and first and last time (epoch millis on the series' clock: the car's own timestamp for the car kinds, the phone's time for VCSEC and the events).

`vehicleId` empty (rows from before multi-car) is exported as `legacy.<kind>.pblog`; the app-state log is
`app.pblog`.

## The databases themselves

For queries instead of decoding, copy the databases with their write-ahead logs, or the newest rows are
missing:

```bash
adb exec-out run-as com.dzid26.teslable sh -c 'cd databases && tar -cf - history.db history.db-wal history.db-shm events.db events.db-wal events.db-shm' > history-db.tar
```

Open the extracted files with the `sqlite3` shell. In `history.db`, `records` holds the car's replies
(`blob` is the encoded `BleRecord`, `time` is epoch millis); in `events.db`, `events` holds the phone-side
logs (`phoneTime`). In both, `series` gives the integer `seriesId` its vehicle, kind and clock, and
`origins` the install's UUID. Copying while the app writes can catch a torn state; prefer the export for
anything you need to trust.

A `history.db.corrupt-<time>` or `events.db.corrupt-<time>` file next to them is a database that failed to open and was kept aside; the
app never deletes it.
