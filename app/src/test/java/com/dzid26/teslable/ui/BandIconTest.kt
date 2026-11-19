// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BandIconTest {
    private val style = IconStyle(sizePx = 16f, insetPx = 6f, airPx = 2f, minSizePx = 8f)

    /** Ten px per hour, over a view that holds the whole chart. */
    private fun canvasX(hours: Double): Float = (hours * 10).toFloat()

    private fun iconsOf(
        vararg bands: Band,
        visible: ClosedFloatingPointRange<Double> = 0.0..100.0,
        canvasX: (Double) -> Float = ::canvasX,
    ): List<BandIcon> = bandIcons(bands.toList(), visible, canvasX, IconEdges(top = 10f, bottom = 110f), style)

    private fun iconOf(band: Band): BandIcon? = iconsOf(band).singleOrNull()

    @Test
    fun aBandTooNarrowForEvenASmallIconGetsNone() {
        // Ten px is one hour: under the smallest sign plus its clearance.
        assertEquals(null, iconOf(Band(0.0, 1.0, BandKind.PARKED)))
    }

    @Test
    fun aWideParkedBandSitsAtTheBottom() {
        // Four hours is forty px, so the sign is full size: centred at 30 px, sixteen px square, above the bottom.
        assertEquals(BandIcon(BandKind.PARKED, left = 22f, top = 94f, right = 38f, bottom = 110f), iconOf(Band(1.0, 5.0, BandKind.PARKED)))
    }

    @Test
    fun aWideSleepBandSitsAtTheTop() {
        assertEquals(BandIcon(BandKind.ASLEEP, left = 22f, top = 10f, right = 38f, bottom = 26f), iconOf(Band(1.0, 5.0, BandKind.ASLEEP)))
        assertEquals(BandIcon(BandKind.CLIMATE, left = 22f, top = 10f, right = 38f, bottom = 26f), iconOf(Band(1.0, 5.0, BandKind.CLIMATE)))
    }

    @Test
    fun aNarrowBandGetsASmallerSign() {
        // Sixteen px of band leaves twelve px of sign after two px of air each side, still centred.
        assertEquals(BandIcon(BandKind.ASLEEP, left = 2f, top = 10f, right = 14f, bottom = 22f), iconOf(Band(0.0, 1.6, BandKind.ASLEEP)))
    }

    @Test
    fun aBandWiderThanTheZoomedViewKeepsItsIconInsideTheView() {
        // The band runs from 2 h to 20 h, the view shows 2 h to 8 h: centre on what is visible (5 h),
        // not on the hidden 11 h, which would land the icon past the plot's right edge.
        val icon = iconsOf(Band(2.0, 20.0, BandKind.ASLEEP), visible = 2.0..8.0).single()
        assertEquals(BandIcon(BandKind.ASLEEP, left = 42f, top = 10f, right = 58f, bottom = 26f), icon)
    }

    @Test
    fun aBandWhollyOutsideTheZoomedViewGetsNone() {
        assertEquals(emptyList<BandIcon>(), iconsOf(Band(0.0, 4.0, BandKind.PARKED), visible = 10.0..20.0))
    }

    @Test
    fun aRightToLeftChartDrawsTheSameIcon() {
        // x runs from the right edge: the band's px land reversed, the icon does not.
        val icon = iconsOf(Band(1.0, 5.0, BandKind.ASLEEP), canvasX = { 100f - canvasX(it) }).single()
        assertEquals(BandIcon(BandKind.ASLEEP, left = 62f, top = 10f, right = 78f, bottom = 26f), icon)
    }

    @Test
    fun theTwoSlotsKeepOverlappingKindsApart() {
        // A sleep inside a parked stretch: both marks are drawn, one at the bottom and one at the top.
        val icons = iconsOf(Band(0.0, 10.0, BandKind.PARKED), Band(2.0, 8.0, BandKind.ASLEEP))
        assertEquals(listOf(94f, 10f), icons.map { it.top })
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
