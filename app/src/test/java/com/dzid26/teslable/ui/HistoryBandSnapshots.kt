// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import com.dzid26.teslable.core.history.HistoryRange
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
}
