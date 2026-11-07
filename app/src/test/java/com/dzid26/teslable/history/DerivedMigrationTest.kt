// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** `derived.db` opened over a file of an exported older version, as a phone that updates the app has it. */
class DerivedMigrationTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Creates `derived.db` at [version] from its exported schema, then runs [setup] on it. */
    private fun createAt(
        version: Int,
        setup: (androidx.sqlite.SQLiteConnection) -> Unit,
    ) {
        val schema = File("schemas/com.dzid26.teslable.history.DerivedDatabase/$version.json").readText()
        val tables = Regex("\"tableName\": \"([^\"]+)\"").findAll(schema).map { it.groupValues[1] }.toList()
        val creates = Regex("\"createSql\": \"([^\"]+)\"").findAll(schema).map { it.groupValues[1] }.toList()
        val setupQueries = Regex("\"setupQueries\": \\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(schema)!!.groupValues[1]
        val connection = BundledSQLiteDriver().open(File(folder.root, DerivedDatabase.FILE_NAME).path)
        tables.zip(creates).forEach { (table, sql) -> connection.execSQL(sql.replace("\${TABLE_NAME}", table)) }
        Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(setupQueries).forEach { connection.execSQL(it.groupValues[1].replace("\\\"", "\"")) }
        connection.execSQL("PRAGMA user_version = $version")
        setup(connection)
        connection.close()
    }

    @Test
    fun `version 3 re-cuts every car's sessions so they get their charger kind`() {
        createAt(2) {
            it.execSQL(
                "INSERT INTO battery_samples (vehicleId, time, level, usableLevel, chargingState, chargeLimit, ratedRangeMiles, " +
                    "chargeEnergyAdded, chargeMilesAddedRated, chargerVoltage, chargeRateMph, readAt) " +
                    "VALUES ('carA', 1000, 50, 50, 'Charging', 80, NULL, 1.0, NULL, 230, NULL, NULL)",
            )
        }

        val db = DerivedDatabase.open(TestContext(folder.root), BundledSQLiteDriver(), log = {})

        assertEquals(listOf(SessionDirtyRow("carA", 0)), db.sessionMarks().dirty())
        db.close()
    }
}
