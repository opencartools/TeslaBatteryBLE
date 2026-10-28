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
    fun aGradientMarksOnlyARiseInPercentThatEndsOnARegeneratingReading() {
        fun at(
            i: Int,
            v: Float,
            regen: Boolean,
            trip: Int = 0,
        ) = Plotted(i * 10.0, v, regenerating = regen, trip = trip, timestampMillis = 0)
        val points =
            listOf(
                at(0, 60f, regen = true),
                at(1, 59f, regen = true), // regenerating but falling
                at(2, 59.5f, regen = true), // rises
                at(3, 60f, regen = false), // rises without regen
                at(4, 59f, regen = true), // falls
                at(5, 61f, regen = true, trip = 1), // new trip
            )
        assertEquals(listOf(listOf(points[1], points[2])), regenGains(points))
    }
}
