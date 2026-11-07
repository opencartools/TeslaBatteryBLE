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
import com.dzid26.teslable.core.health.SessionPrice
import com.dzid26.teslable.core.health.StoredChargePrice

/**
 * One charge session's stored price ([StoredChargePrice]): [kind] is `PerKwh` or `Total`, [value] the price per kWh
 * or the total, both null for an empty price, in [currency] (an ISO 4217 code, resolved when stored). [byOwner] is
 * an edit; otherwise the automatic price frozen when the session was finished. [place] is where it charged
 * as the owner set it (`ChargePlace` or their own name), null unless set. Keyed by car and the session's start and end
 * as last cut; each build matches a row to its re-cut session by overlap
 * and writes it at that session's start and end, where readers read it.
 */
@Entity(tableName = "charge_prices", primaryKeys = ["vehicleId", "startTime", "endTime"])
internal data class ChargePriceRow(
    val vehicleId: String,
    val startTime: Long,
    val endTime: Long,
    val kind: String?,
    val value: Float?,
    val currency: String,
    val byOwner: Boolean,
    val place: String?,
)

@Dao
internal interface ChargePriceDao {
    @Query("SELECT * FROM charge_prices ORDER BY vehicleId, startTime")
    fun all(): List<ChargePriceRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(rows: List<ChargePriceRow>)

    @Query("DELETE FROM charge_prices WHERE vehicleId = :vehicleId AND startTime = :startTime AND endTime = :endTime")
    fun delete(
        vehicleId: String,
        startTime: Long,
        endTime: Long,
    )

    /** Takes out [removed] (by key) and writes [written], in one transaction. */
    @Transaction
    fun replace(
        removed: List<ChargePriceRow>,
        written: List<ChargePriceRow>,
    ) {
        removed.forEach { delete(it.vehicleId, it.startTime, it.endTime) }
        put(written)
    }
}

internal fun StoredChargePrice.toRow(): ChargePriceRow =
    when (val p = price) {
        is SessionPrice.PerKwh -> ChargePriceRow(vehicleId, startMillis, endMillis, PER_KWH, p.pricePerKwh, currencyCode, byOwner, place)
        is SessionPrice.Total -> ChargePriceRow(vehicleId, startMillis, endMillis, TOTAL, p.amount, currencyCode, byOwner, place)
        null -> ChargePriceRow(vehicleId, startMillis, endMillis, null, null, currencyCode, byOwner, place)
    }

/** The price this row holds; null for a kind this version does not know, which is then left as it is. */
internal fun ChargePriceRow.toStored(): StoredChargePrice? {
    val price: SessionPrice? =
        when (kind) {
            null -> null
            PER_KWH -> value?.let { SessionPrice.PerKwh(it) }
            TOTAL -> value?.let { SessionPrice.Total(it) }
            else -> null
        }
    val known = kind == null || price != null
    return StoredChargePrice(vehicleId, startTime, endTime, price, currency, byOwner, place).takeIf { known }
}

private const val PER_KWH = "PerKwh"
private const val TOTAL = "Total"

/**
 * `owner.db`: what the owner entered, the one part of the history that cannot be rebuilt (ADR-0014). It is small,
 * kept apart from `derived.db` so that one can be rebuilt or reset freely, and it is the only database in the
 * backup. Its journal is not WAL, so the backed-up file always holds every committed row. Its exported schema is
 * binding from the first normal release: every later change ships a migration.
 */
@Database(entities = [ChargePriceRow::class], version = 1, exportSchema = true)
internal abstract class OwnerDatabase : RoomDatabase() {
    abstract fun chargePrices(): ChargePriceDao

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
