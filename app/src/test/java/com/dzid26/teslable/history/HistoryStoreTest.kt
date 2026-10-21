// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.BleRecord
import com.dzid26.teslable.core.history.ProtoLog
import com.tesla.generated.carserver.vehicle.ChargeState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipInputStream
import kotlin.coroutines.CoroutineContext

private object InlineDispatcher : CoroutineDispatcher() {
    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) = block.run()
}

/** [HistoryStore]'s public API over the Room-backed records (ADR-0014). */
class HistoryStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val historyDir get() = File(folder.root, "battery-history")
    private lateinit var db: HistoryDatabase

    @Before
    fun open() {
        db = testDatabase(folder.root)
    }

    @After
    fun close() = db.close()

    // InlineDispatcher runs the store's work on the calling thread, so each call has finished when it returns.
    private fun store() = HistoryStore(historyDir, lazy { RoomBackedRecordStore(db, "phone-a") }, CoroutineScope(InlineDispatcher))

    private fun reply(seconds: Long) = ChargeState(timestamp = Instant.ofEpochSecond(seconds), battery_level = 70)

    @Test
    fun `a charge reply reaches the samples and survives a restart`() {
        val now = Instant.now()
        store().record("carA", reply(now.epochSecond - 60), now, rssi = -55)

        val first = store()
        assertEquals(listOf((now.epochSecond - 60) * 1000), first.samples.value.map { it.timestampMillis })
        assertEquals(
            70,
            first.samples.value
                .single()
                .batteryLevel,
        )
    }

    @Test
    fun `the files of an earlier version are imported on the first start`() {
        val now = Instant.now()
        val dir = historyDir.apply { mkdirs() }
        val record = BleRecord(device_timestamp = now, charge_state = reply(now.epochSecond - 30))
        File(dir, "carA.charge.pblog").writeBytes(ProtoLog.encode(listOf(record)))

        val imported = store()

        assertEquals(listOf((now.epochSecond - 30) * 1000), imported.samples.value.map { it.timestampMillis })
        assertEquals(true, File(dir, "carA.charge.pblog.imported").isFile)
    }

    @Test
    fun `exportLogs zips the records as pblog files`() {
        val now = Instant.now()
        val store = store()
        store.record("carA", reply(now.epochSecond - 60), now, rssi = null)

        val out = ByteArrayOutputStream()
        val exported = runBlocking { store.exportLogs(out) }

        assertEquals(1, exported)
        val names =
            ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
                generateSequence { zip.nextEntry }.map { it.name }.toList()
            }
        assertEquals(listOf("carA.charge.pblog"), names)
    }
}
