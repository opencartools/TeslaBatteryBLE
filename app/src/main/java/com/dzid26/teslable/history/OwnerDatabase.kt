// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.sqlite.SQLiteDriver
import com.dzid26.teslable.core.health.ChargePriceOverride
import com.dzid26.teslable.core.health.SessionPrice

/**
 * The price the owner set for one charge session ([ChargePriceOverride]): [kind] is `PerKwh` or `Total`, [value]
 * the price per kWh or the total, in [currency] (an ISO 4217 code, resolved when it was set). Keyed by car and the
 * session's start as last cut; readers match a row to a session by overlap.
 */
@Entity(tableName = "charge_price_overrides", primaryKeys = ["vehicleId", "startTime"])
internal data class ChargePriceOverrideRow(
    val vehicleId: String,
    val startTime: Long,
    val endTime: Long,
    val kind: String,
    val value: Float,
    val currency: String,
)

@Dao
internal interface ChargePriceOverrideDao {
    @Query("SELECT * FROM charge_price_overrides ORDER BY vehicleId, startTime")
    fun all(): List<ChargePriceOverrideRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(row: ChargePriceOverrideRow)

    @Query("DELETE FROM charge_price_overrides WHERE vehicleId = :vehicleId AND startTime = :startTime")
    fun delete(
        vehicleId: String,
        startTime: Long,
    )

    /** Moves each row to its session's new times: the old key out, the moved row in. */
    @Transaction
    fun move(moves: List<Pair<ChargePriceOverrideRow, ChargePriceOverrideRow>>) {
        moves.forEach { (old, moved) ->
            delete(old.vehicleId, old.startTime)
            put(moved)
        }
    }
}

internal fun ChargePriceOverride.toRow(): ChargePriceOverrideRow =
    when (val p = price) {
        is SessionPrice.PerKwh -> ChargePriceOverrideRow(vehicleId, startMillis, endMillis, PER_KWH, p.pricePerKwh, currencyCode)
        is SessionPrice.Total -> ChargePriceOverrideRow(vehicleId, startMillis, endMillis, TOTAL, p.amount, currencyCode)
    }

/** The override this row holds; null for a kind this version does not know. */
internal fun ChargePriceOverrideRow.toOverride(): ChargePriceOverride? {
    val price =
        when (kind) {
            PER_KWH -> SessionPrice.PerKwh(value)
            TOTAL -> SessionPrice.Total(value)
            else -> return null
        }
    return ChargePriceOverride(vehicleId, startTime, endTime, price, currency)
}

private const val PER_KWH = "PerKwh"
private const val TOTAL = "Total"

/**
 * `owner.db`: what the owner entered, the one part of the history that cannot be rebuilt (ADR-0014). It is small,
 * kept apart from `derived.db` so that one can be rebuilt or reset freely, and it is the only database in the
 * backup. Its journal is not WAL, so the backed-up file always holds every committed row. Its exported schema is
 * binding from the first normal release: every later change ships a migration.
 */
@Database(entities = [ChargePriceOverrideRow::class], version = 1, exportSchema = true)
internal abstract class OwnerDatabase : RoomDatabase() {
    abstract fun chargePrices(): ChargePriceOverrideDao

    companion object {
        const val FILE_NAME = "owner.db"

        /** Opens `owner.db`; one that does not open is kept aside, never deleted (see [openQuarantining]). */
        fun open(
            context: Context,
            driver: SQLiteDriver? = null,
            log: (String) -> Unit = defaultLog(),
        ): OwnerDatabase =
            openQuarantining(
                context = context,
                fileName = FILE_NAME,
                log = log,
                build = { build(context, driver) },
                inMemory = { inMemory(context, driver != null, driver) },
            )

        /** An empty database that lives in memory: the last-resort fallback, and the tests'. */
        fun inMemory(
            context: Context,
            onMainThread: Boolean = false,
            driver: SQLiteDriver? = null,
        ): OwnerDatabase =
            Room
                .inMemoryDatabaseBuilder(context, OwnerDatabase::class.java)
                .apply {
                    if (onMainThread) allowMainThreadQueries()
                    if (driver != null) setDriver(driver)
                }.build()

        private fun build(
            context: Context,
            driver: SQLiteDriver?,
        ): OwnerDatabase =
            Room
                .databaseBuilder(context, OwnerDatabase::class.java, FILE_NAME)
                .setJournalMode(JournalMode.TRUNCATE)
                .apply {
                    if (driver != null) setDriver(driver).allowMainThreadQueries()
                }.build()
    }
}
