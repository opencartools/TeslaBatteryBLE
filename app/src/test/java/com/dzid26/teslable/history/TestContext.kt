// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import android.content.Context
import android.content.ContextWrapper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

/**
 * A [Context] stub for building the history database on the JVM: Room only asks it for database paths
 * (the unit tests return default values for every other Android call).
 */
internal class TestContext(
    private val dir: File,
) : ContextWrapper(null) {
    override fun getDatabasePath(name: String): File = File(dir, name)

    override fun deleteDatabase(name: String): Boolean = getDatabasePath(name).delete()

    override fun getApplicationContext(): Context = this

    // Room asks for the activity service to tell low-RAM devices; none here.
    override fun getSystemService(name: String): Any? = null
}

/** The in-memory history database over the bundled SQLite driver, usable from the test thread. */
internal fun testDatabase(dir: File): HistoryDatabase =
    HistoryDatabase.inMemory(TestContext(dir), onMainThread = true, driver = BundledSQLiteDriver())
