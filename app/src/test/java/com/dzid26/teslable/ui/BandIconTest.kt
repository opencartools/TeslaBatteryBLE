// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BandIconTest {
    private val style = IconStyle(sizePx = 16f, insetPx = 6f, minBandPx = 32f)

    /** Ten px per hour, over a view that holds the whole chart. */
    private fun canvasX(hours: Double): Float = (hours * 10).toFloat()

    private fun iconOf(
        band: Band,
        visible: ClosedFloatingPointRange<Double> = 0.0..100.0,
        canvasX: (Double) -> Float = ::canvasX,
    ): BandIcon? = bandIcons(listOf(band), visible, canvasX, top = 10f, style = style).singleOrNull()

    @Test
    fun aBandTooNarrowForAnIconGetsNone() {
        // Two hours is twenty px: under the icon and its air.
        assertEquals(null, iconOf(Band(0.0, 2.0, BandKind.PARKED)))
    }

    @Test
    fun aWideBandCentresItsIconUnderTheTop() {
        // Four hours is forty px, so the icon is legal: centred at 30 px, sixteen px square, six px down.
        assertEquals(BandIcon(BandKind.PARKED, left = 22f, top = 10f, right = 38f, bottom = 26f), iconOf(Band(1.0, 5.0, BandKind.PARKED)))
    }

    @Test
    fun aBandWiderThanTheZoomedViewKeepsItsIconInsideTheView() {
        // The band runs from 2 h to 20 h, the view shows 2 h to 8 h: centre on what is visible (5 h),
        // not on the hidden 11 h, which would land the icon past the plot's right edge.
        val icon = iconOf(Band(2.0, 20.0, BandKind.PARKED), visible = 2.0..8.0)
        assertEquals(BandIcon(BandKind.PARKED, left = 42f, top = 10f, right = 58f, bottom = 26f), icon)
    }

    @Test
    fun aBandWhollyOutsideTheZoomedViewGetsNone() {
        assertEquals(null, iconOf(Band(0.0, 4.0, BandKind.PARKED), visible = 10.0..20.0))
    }

    @Test
    fun aRightToLeftChartDrawsTheSameIcon() {
        // x runs from the right edge: the band's px land reversed, the icon does not.
        val icon = iconOf(Band(1.0, 5.0, BandKind.PARKED), canvasX = { 100f - canvasX(it) })
        assertEquals(BandIcon(BandKind.PARKED, left = 62f, top = 10f, right = 78f, bottom = 26f), icon)
    }

    @Test
    fun aTouchLandsOnAnIconWithFingerAllowance() {
        val icon = BandIcon(BandKind.PARKED, left = 0f, top = 0f, right = 16f, bottom = 16f)
        assertTrue(icon.touches(Offset(8f, 8f), slop = 0f))
        // Just outside the square, inside the allowance.
        assertTrue(icon.touches(Offset(20f, 8f), slop = 6f))
        assertFalse(icon.touches(Offset(30f, 8f), slop = 6f))
    }
}
