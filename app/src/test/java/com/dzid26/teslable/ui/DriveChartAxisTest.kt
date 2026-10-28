package com.dzid26.teslable.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveChartAxisTest {
    private fun axes(vararg values: Float) =
        axisSpec(
            values.mapIndexed { i, v ->
                Plotted(i * 10.0, v, regenerating = false, trip = 0, timestampMillis = 0)
            },
        )

    @Test
    fun aRangeTopsOutAt100WithHeadroomAboveItAndLabelsOnTheBound() {
        val spec = axes(96f, 100f)
        assertEquals(100.0, spec.yMax, 0.0)
        assertEquals(100.0 + (100.0 - spec.yMin) * Y_HEADROOM_SHARE, spec.range.endInclusive, 1e-9)
        assertEquals(100.0, spec.gridlines.last(), 0.0)
        assertEquals(spec.yMin, spec.range.start, 0.0)
    }

    @Test
    fun aRangeReachingZeroGetsHeadroomBelowIt() {
        val spec = axes(0f, 10f)
        assertEquals(0.0, spec.yMin, 0.0)
        assertTrue(spec.range.start < 0.0)
        assertEquals(0.0, spec.gridlines.first(), 0.0)
    }

    @Test
    fun aMidRangeIsNotPaddedAndLabelsStepFromItsBottom() {
        val spec = axes(40f, 60f)
        assertEquals(spec.yMin, spec.range.start, 0.0)
        assertEquals(spec.yMax, spec.range.endInclusive, 0.0)
        assertTrue(spec.gridlines.zipWithNext().all { (a, b) -> Math.abs(b - a - spec.yStep) < 1e-9 })
    }

    @Test
    fun cursorTextJoinsPercentDistanceAndTime() {
        assertEquals("71.4% · 12.3mi · Oct 26 14:05", driveCursorText(71.4f, "12.3mi", "Oct 26 14:05"))
    }

    @Test
    fun aGradientMarksTwoRegeneratingReadingsThatHeldTheRatedRange() {
        fun at(
            i: Int,
            miles: Float?,
            regen: Boolean = true,
            trip: Int = 0,
        ) = Plotted(i * 10.0, 60f, regenerating = regen, trip = trip, timestampMillis = 0, ratedMiles = miles)
        val points =
            listOf(
                at(0, 200f),
                at(1, 199f), // drops
                at(2, 199f), // holds
                at(3, 199.5f), // rises
                at(4, 199.5f, regen = false), // not regenerating
                at(5, 199.5f), // not next to a regenerating reading
                at(6, null), // no rated range
                at(7, 205f),
                at(8, 205f, trip = 1), // new trip
            )
        assertEquals(listOf(listOf(points[1], points[2]), listOf(points[2], points[3])), regenHolds(points))
    }
}
