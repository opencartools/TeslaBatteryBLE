// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.cash.paparazzi.Paparazzi
import com.dzid26.teslable.ble.LogEntry
import org.junit.Rule
import org.junit.Test

/** Paparazzi snapshots of the log card: its title row with the Share button, empty and filled, and mirrored. */
class LogCardSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun logCardEmpty() = paparazzi.snapCard("log-card_empty") { LogCard(emptyList()) }

    @Test
    fun logCardLines() = paparazzi.snapCard("log-card_lines") { LogCard(lines) }

    @Test
    fun logCardRtl() =
        paparazzi.snapCard("log-card_rtl") {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { LogCard(lines) }
        }

    private companion object {
        val lines =
            listOf(
                LogEntry(null, "Scanning for Tesla advertisements (name pattern S<hex>C)"),
                LogEntry(null, "Scan ended: 31 advertisements seen, 4 with a name, 1 Tesla"),
                LogEntry("S1f094173C", "S1f094173C: connected"),
            )
    }
}
