// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/** The SOC bar: the stop dot at the charge limit, no dot without a limit, and a dedicated RTL snapshot. */
class SocBarSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun socBar() =
        paparazzi.snapCard("soc-bar") {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SocBar(percent = 48, limit = 90, color = MaterialTheme.colorScheme.primary)
                SocBar(percent = 90, limit = 90, color = MaterialTheme.colorScheme.primary)
                SocBar(percent = 95, limit = 90, color = MaterialTheme.colorScheme.primary)
                SocBar(percent = 48, limit = 90, color = MaterialTheme.colorScheme.outline)
                SocBar(percent = 48, limit = null, color = MaterialTheme.colorScheme.primary)
            }
        }

    @Test
    fun socBarRtl() =
        paparazzi.snapCard("soc-bar-rtl") {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                SocBar(percent = 48, limit = 90, color = MaterialTheme.colorScheme.primary)
            }
        }
}
