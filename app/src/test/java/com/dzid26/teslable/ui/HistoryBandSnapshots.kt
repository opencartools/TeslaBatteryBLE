// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import com.dzid26.teslable.core.history.ActivityKind
import com.dzid26.teslable.core.history.ActivitySample
import com.dzid26.teslable.core.history.HistoryRange
import com.dzid26.teslable.core.history.ProjectionContext
import com.dzid26.teslable.core.history.StatusSample
import com.dzid26.teslable.ui.CardSnapshots.Companion.BLE_NAME
import com.dzid26.teslable.ui.CardSnapshots.Companion.NOW
import com.dzid26.teslable.ui.CardSnapshots.Companion.ODOMETER
import com.dzid26.teslable.ui.CardSnapshots.Companion.historySample
import com.dzid26.teslable.ui.CardSnapshots.Companion.parkedDrive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Paparazzi snapshots of the power-down bands behind the history chart. */
class HistoryBandSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    private val defaultLocale: Locale = Locale.getDefault()

    // Chart times read the default locale; pin it so renders match across machines.
    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun historyCardParkedBands() {
        // Two drives split the day into three quiet gaps: 2 h, 6 h and 2 h before the last read.
        paparazzi.snapCard("history-card_parked-bands") {
            HistoryCard(
                samples = bandSamples,
                drives = bandSamples.zip(bandOdometers).map { (sample, odometer) -> parkedDrive(sample, odometer) },
                view = HistoryView(nowMillis = NOW),
            )
        }
    }

    @Test
    fun historyCardParkedBandsStale() {
        // The last read is twenty hours old, past the twelve-hour horizon of a day range: no
        // projection line, but the quiet stretches are still shaded, clipped at the window's edge.
        paparazzi.snapCard("history-card_parked-bands-stale") {
            HistoryCard(
                samples = staleSamples,
                drives = staleSamples.map { parkedDrive(it) },
                view = HistoryView(nowMillis = NOW, range = HistoryRange.DAY),
            )
        }
    }

    @Test
    fun historyCardParkedBandsSigns() {
        // Six hours on screen: the two-hour bands are wide enough to carry their parking signs.
        paparazzi.snapCard("history-card_parked-bands-signs") {
            HistoryCard(
                samples = signSamples,
                drives = signSamples.zip(signOdometers).map { (sample, odometer) -> parkedDrive(sample, odometer) },
                view = HistoryView(nowMillis = NOW, range = HistoryRange.SIX_HOURS),
            )
        }
    }

    @Test
    fun historyCardBandsKinds() {
        // A sleep inside the parked stretch, then a climate run that makes the second stretch not quiet:
        // gray under an indigo moon, and amber under a snowflake.
        paparazzi.snapCard("history-card_bands-kinds") {
            HistoryCard(
                samples = kindSamples,
                drives = kindSamples.map { parkedDrive(it) },
                context = ProjectionContext(statuses = kindStatuses, activity = kindActivity),
                view = HistoryView(nowMillis = NOW),
            )
        }
    }

    @Test
    fun historyCardSleepPeriods() {
        // Seven nightly sleeps over a week, one with a hole the log missed: the sleep bands as they read on 7d.
        paparazzi.snapCard("history-card_sleep-periods") {
            HistoryCard(
                samples = sleepSamples,
                drives = sleepSamples.zip(sleepOdometers).map { (sample, odometer) -> parkedDrive(sample, odometer) },
                context = ProjectionContext(statuses = sleepStatuses),
                view = HistoryView(nowMillis = NOW, range = HistoryRange.WEEK),
            )
        }
    }

    @Test
    fun historyCardBandsOverlap() {
        // A climate run inside the sleep's log hole: the amber covers the stripes, and the moon stays on a witnessed part.
        paparazzi.snapCard("history-card_bands-overlap") {
            HistoryCard(
                samples = kindSamples,
                drives = kindSamples.map { parkedDrive(it) },
                context = ProjectionContext(statuses = kindStatuses, activity = gapActivity),
                view = HistoryView(nowMillis = NOW),
            )
        }
    }

    @Test
    fun bandIconBubble() {
        // The explanation bubble, seeded with the sign rect a draw would record: a snapshot cannot tap.
        paparazzi.snapCard("band-icon_bubble") {
            Box(Modifier.fillMaxWidth().height(80.dp)) {
                BandIconBubble(BandIcons().apply { open = BandIcon(BandKind.PARKED, 140f, 8f, 156f, 24f) }, chartWidthPx = 1080)
            }
        }
    }

    private val bandSamples =
        listOf(
            historySample(23, 72),
            historySample(21, 71),
            historySample(16, 70),
            historySample(10, 69),
            historySample(2, 67),
            historySample(0, 66),
        )

    // The odometer each read is stored at: a difference between two of them is a drive the phone saw.
    private val bandOdometers =
        listOf(
            ODOMETER,
            ODOMETER,
            ODOMETER + 300,
            ODOMETER + 300,
            ODOMETER + 800,
            ODOMETER + 800,
        )

    private val staleSamples =
        listOf(
            historySample(30, 70),
            historySample(26, 69),
            historySample(22, 68),
            historySample(20, 67),
        )

    private val signSamples =
        listOf(
            historySample(5, 74),
            historySample(3, 73),
            historySample(2, 72),
            historySample(0, 71),
        )

    // A drive between the third and second read keeps its two quiet stretches apart.
    private val signOdometers =
        listOf(
            ODOMETER,
            ODOMETER,
            ODOMETER + 300,
            ODOMETER + 300,
        )

    // Two five-hour parked gaps, the first quiet, the second kept busy by the climate.
    private val kindSamples =
        listOf(
            historySample(10, 72),
            historySample(5, 71),
            historySample(0, 70),
        )

    // Asleep from nine hours ago to six, with an hour and a half the log missed in the middle: the band is bridged
    // across it and the unseen stretch is striped.
    private val kindStatuses =
        buildList {
            var minutesAgo = 9 * 60L
            while (minutesAgo >= 8 * 60L + 30) {
                add(StatusSample(NOW - minutesAgo * MINUTE, BLE_NAME, asleep = true, userPresent = false, locked = true))
                minutesAgo -= 10
            }
            minutesAgo = 7 * 60L
            while (minutesAgo >= 6 * 60L) {
                add(StatusSample(NOW - minutesAgo * MINUTE, BLE_NAME, asleep = true, userPresent = false, locked = true))
                minutesAgo -= 10
            }
            add(StatusSample(NOW - (6 * 60L - 10) * MINUTE, BLE_NAME, asleep = false, userPresent = false, locked = true))
        }

    // The climate ran for two hours inside the second gap, a reading every ten minutes.
    private val kindActivity =
        buildList {
            var minutesAgo = 4 * 60L
            while (minutesAgo > 2 * 60L) {
                add(ActivitySample(NOW - minutesAgo * MINUTE, BLE_NAME, ActivityKind.CLIMATE, on = true))
                minutesAgo -= 10
            }
            add(ActivitySample(NOW - 2 * 60L * MINUTE, BLE_NAME, ActivityKind.CLIMATE, on = false))
        }

    // The climate ran inside the sleep's unseen hole, eight twenty to seven twenty ago, so the stripes show around it.
    private val gapActivity =
        buildList {
            var minutesAgo = 8 * 60L + 20
            while (minutesAgo > 7 * 60L + 20) {
                add(ActivitySample(NOW - minutesAgo * MINUTE, BLE_NAME, ActivityKind.CLIMATE, on = true))
                minutesAgo -= 10
            }
            add(ActivitySample(NOW - (7 * 60L + 20) * MINUTE, BLE_NAME, ActivityKind.CLIMATE, on = false))
        }

    // A read every six hours across the week, so the chart has a line to draw.
    private val sleepSamples =
        (0..28).map { step ->
            historySample((28 - step) * 6L, 78 - step / 2)
        }

    // Every read shows the odometer moved, so no parked stretch is shaded: this snapshot is about the sleeps.
    private val sleepOdometers = sleepSamples.indices.map { ODOMETER + it * 2_400 }

    // A sleep every night from six hours ago back, eight hours each; the one three nights back lost ninety minutes.
    private val sleepStatuses =
        buildList {
            for (night in 0..6) {
                val end = (6 + night * 24) * 60L
                val start = end + 8 * 60
                val holeFrom = start - 15
                val holeTo = start - 105
                var minute = start
                while (minute >= end) {
                    val unseen = night == 3 && minute < holeFrom && minute > holeTo
                    if (!unseen) {
                        add(StatusSample(NOW - minute * MINUTE, BLE_NAME, asleep = true, userPresent = false, locked = true))
                    }
                    minute -= 15
                }
                add(StatusSample(NOW - (end - 5) * MINUTE, BLE_NAME, asleep = false, userPresent = false, locked = true))
            }
        }.sortedBy { it.timestampMillis }
}

private const val MINUTE = 60_000L
