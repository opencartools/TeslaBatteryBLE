// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.dynamicColorScheme

/**
 * The Material You scheme the app renders when dynamic color is on and the
 * wallpaper is the default blue one (the old emulator screenshots), built from
 * a seed like Android does. Paparazzi has no wallpaper, so the seed is fixed;
 * it is the light primary sampled from `website/images/01-trip.png`.
 */
internal fun demoColorScheme(darkTheme: Boolean): ColorScheme =
    dynamicColorScheme(seedColor = Color(DEMO_SEED), isDark = darkTheme, isAmoled = false)

private const val DEMO_SEED = 0xFF445E91

private const val STATUS_BAR_DP = 40
private const val NAV_BAR_DP = 24
private const val PILL_WIDTH_DP = 134
private const val PILL_HEIGHT_DP = 4

/**
 * The screen between a fake status bar (clock and signal glyphs) and gesture
 * nav pill, as the app sits on a phone; Paparazzi draws no system bars of its
 * own in the render mode the cards need.
 */
@Composable
internal fun DemoSystemBars(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = demoColorScheme(darkTheme)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(STATUS_BAR_DP.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = "9:00", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        glyph(width = 14, height = 10)
                        glyph(width = 14, height = 10)
                        glyph(width = 8, height = 14)
                    }
                }
                Box(modifier = Modifier.weight(1f)) { content() }
                Box(
                    modifier = Modifier.fillMaxWidth().height(NAV_BAR_DP.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .width(PILL_WIDTH_DP.dp)
                                .height(PILL_HEIGHT_DP.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(MaterialTheme.colorScheme.onSurface),
                    )
                }
            }
        }
    }
}

@Composable
private fun glyph(
    width: Int,
    height: Int,
) {
    Box(
        modifier =
            Modifier
                .size(width = width.dp, height = height.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurface),
    )
}
