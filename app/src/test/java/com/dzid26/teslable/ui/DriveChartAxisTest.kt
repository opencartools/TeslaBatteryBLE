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
    fun onlyALoneRegeneratingReadingGetsAGreenDot() {
        fun at(
            i: Int,
            regen: Boolean,
            trip: Int = 0,
        ) = Plotted(i * 10.0, 60f, regenerating = regen, trip = trip, timestampMillis = 0)
        val points =
            listOf(
                at(0, regen = true), // starts a stretch with the next
                at(1, regen = true),
                at(2, regen = false),
                at(3, regen = true), // alone
                at(4, regen = false),
                at(5, regen = true, trip = 0), // next to a regenerating reading of another trip
                at(6, regen = true, trip = 1),
            )
        assertEquals(listOf(points[3], points[5], points[6]), loneRegenPoints(points))
    }
}
