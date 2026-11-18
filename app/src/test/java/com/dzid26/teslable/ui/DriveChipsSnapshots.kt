// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import com.dzid26.teslable.core.history.DriveWindow
import com.dzid26.teslable.core.units.DistanceUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The distance-window chips at the Jelly Star's content width: two lines are expected at most, in miles and in
 * kilometres. A third line means one chip sits on a line of its own, so the row wasted a line on it.
 */
class DriveChipsSnapshots {
    // The width comes from the modifier, so any device renders the same row; the Jelly Star's density is not needed.
    @get:Rule
    val paparazzi = Paparazzi()

    private val defaultLocale: Locale = Locale.getDefault()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun windowChipsMiles() = snapChips("drive-chips_miles", DistanceUnit.MILES)

    @Test
    fun windowChipsKilometers() = snapChips("drive-chips_km", DistanceUnit.KILOMETERS)

    private fun snapChips(
        name: String,
        unit: DistanceUnit,
    ) {
        var lines by mutableStateOf(0)
        paparazzi.snapshot(name) {
            val chipHeightPx = with(LocalDensity.current) { CHIP_TOUCH_TARGET.toPx() }
            CardFrame(darkTheme = false) {
                CompositionLocalProvider(LocalDistanceUnit provides unit) {
                    DriveChips(
                        window = DriveWindow.SINCE_CHARGE,
                        unit = unit,
                        modifier =
                            Modifier.width(JELLY_CONTENT_WIDTH).onGloballyPositioned {
                                lines = (it.size.height / chipHeightPx).roundToInt()
                            },
                    ) {}
                }
            }
        }
        assertTrue("the window chips take $lines lines in $unit at $JELLY_CONTENT_WIDTH", lines <= MAX_LINES)
    }
}

/**
 * The Jelly Star's 240 dp screen less the car screen's 16 dp insets and the card's 12 dp padding: what the card's
 * content actually gets.
 */
private val JELLY_CONTENT_WIDTH = 184.dp

/** A chip's measured height: Material3's 48 dp minimum touch target, which the chips fill. */
private val CHIP_TOUCH_TARGET = 48.dp

/** Three window chips plus "Since charge" may wrap to two lines; a third puts a single chip on a line. */
private const val MAX_LINES = 2
