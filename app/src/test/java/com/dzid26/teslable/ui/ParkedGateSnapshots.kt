// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import app.cash.paparazzi.Paparazzi
import com.dzid26.teslable.core.history.HistoryRange
import com.dzid26.teslable.ui.CardSnapshots.Companion.NOW
import com.dzid26.teslable.ui.CardSnapshots.Companion.historySample
import com.dzid26.teslable.ui.CardSnapshots.Companion.parkedDrive
import com.dzid26.teslable.ui.CardSnapshots.Companion.projectionSample
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Paparazzi snapshots of the parked projection's gates: line, band, and neither. */
class ParkedGateSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    private val defaultLocale: Locale = Locale.getDefault()

    // Chart times read the default locale; pin it so renders match across machines.
    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun historyCardParkedLineOnly() {
        // Two power-down pairs: the line shows, the band does not.
        paparazzi.snapCard("history-card_parked-line-only") {
            HistoryCard(
                samples = lineOnlySamples,
                drives = lineOnlySamples.map { parkedDrive(it) },
                view = HistoryView(nowMillis = NOW),
            )
        }
    }

    @Test
    fun historyCardParkedNoLine() {
        // A read every twenty minutes: no power-down pair, so no line.
        paparazzi.snapCard("history-card_parked-no-line") {
            HistoryCard(
                samples = noLineSamples,
                drives = noLineSamples.map { parkedDrive(it) },
                view = HistoryView(nowMillis = NOW),
            )
        }
    }

    @Test
    fun historyCardParkedNoProjection() {
        // The last read is twenty hours old, past the twelve-hour horizon of a day range.
        paparazzi.snapCard("history-card_parked-no-projection") {
            HistoryCard(
                samples = noProjectionSamples,
                drives = noProjectionSamples.map { parkedDrive(it) },
                view = HistoryView(nowMillis = NOW, range = HistoryRange.DAY),
            )
        }
    }

    private val lineOnlySamples =
        listOf(
            historySample(4, 60),
            historySample(2, 59),
            historySample(0, 58),
        )

    private val noLineSamples =
        (0..6).map { step ->
            projectionSample((6 - step) * 20L, 60 - step / 2, charging = false, ratedMiles = 180f - step * 1.5f)
        }

    private val noProjectionSamples =
        listOf(
            historySample(30, 70),
            historySample(26, 69),
            historySample(22, 68),
            historySample(20, 67),
        )
}
