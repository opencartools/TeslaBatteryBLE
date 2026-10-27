// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import android.content.Context
import android.util.Log
import androidx.room.RoomDatabase
import androidx.room.useReaderConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.runBlocking
import java.io.File

private const val TAG = "HistoryDatabase"

/** Logs to logcat; the tests pass a no-op instead, as `android.util.Log` is not available on the JVM. */
internal fun defaultLog(): (String) -> Unit = { Log.w(TAG, it) }

/** WAL with `synchronous=NORMAL`, set whenever a database opens (ADR-0014). */
internal object SynchronousNormal : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        db.query("PRAGMA synchronous = NORMAL").close()
    }
}

/**
 * Opens a database file with the rule of ADR-0014: one that does not open (damaged, or a schema no
 * migration covers) is never deleted. It is renamed to `<file>.corrupt-<time>` (with its `-wal` and
 * `-shm`) and a new one is created; if that fails too, the history runs from an empty in-memory database
 * (logged once through [log]), so a damaged file never crashes the app.
 */
internal fun <T : RoomDatabase> openQuarantining(
    context: Context,
    fileName: String,
    log: (String) -> Unit,
    build: () -> T,
    inMemory: () -> T,
): T {
    // Builds and opens the database; one that fails to open is closed again so its files can move.
    fun opened(): T {
        val db = build()
        return runCatching { db.also { it.openHelperProbe() } }
            .onFailure { runCatching { db.close() } }
            .getOrThrow()
    }
    return runCatching { opened() }
        .recoverCatching {
            quarantine(context, fileName)
            opened()
        }.getOrElse {
            log("$fileName did not open; its records run in memory until the app restarts (${it.javaClass.simpleName})")
            inMemory()
        }
}

/** Opens the file now, so a damaged one fails here and not on the first write. */
private fun RoomDatabase.openHelperProbe() {
    runBlocking { useReaderConnection { connection -> connection.usePrepared("PRAGMA schema_version") { it.step() } } }
}

/** Moves the database files aside under a name no later quarantine reuses. */
private fun quarantine(
    context: Context,
    fileName: String,
) {
    val stamp = System.currentTimeMillis()
    for (suffix in listOf("", "-wal", "-shm")) {
        val file = context.getDatabasePath(fileName + suffix)
        if (file.exists()) file.renameTo(File(file.parentFile, "$fileName.corrupt-$stamp$suffix"))
    }
}

/**
 * The bytes of pages in use, `(page_count - freelist_count) * page_size`. A trim frees pages but the
 * file keeps its length, so the file size alone would never fall back under the cap. Call it outside a
 * transaction: it reads over a separate connection.
 */
internal fun RoomDatabase.usedBytes(): Long =
    runBlocking {
        useReaderConnection { connection ->
            suspend fun pragma(name: String): Long =
                connection.usePrepared("PRAGMA $name") {
                    it.step()
                    it.getLong(0)
                }
            (pragma("page_count") - pragma("freelist_count")) * pragma("page_size")
        }
    }
