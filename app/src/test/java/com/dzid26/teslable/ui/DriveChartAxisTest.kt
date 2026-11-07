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
    fun aZoomedViewFitsThePercentAxisToWhatIsOnScreen() {
        // 0 to 100 km falling from 90% to 40%; the middle fifth (40-60 km, about 70-60%) on screen.
        val points = (0..10).map { Plotted(it * 10.0, 90f - it * 5f, regenerating = false, trip = 0, timestampMillis = 0) }
        val viewport = ChartViewport().apply { layerWidth = 100f }
        viewport.transform(previousX = 50f, x = 50f, previousSpan = 10f, span = 50f, minFingerSpan = 8f)
        val full = axisSpec(points)
        val view = full.view(points, points.map { it.distance }, viewport)
        assertEquals(40.0, view.xMin, 1e-9)
        assertEquals(60.0, view.xMax, 1e-9)
        // The points at 30 and 70 km, just past the edges, are in the fit: 75% down to 55%, padded to whole steps.
        assertTrue(view.yMin in 50.0..55.0)
        assertTrue(view.yMax in 75.0..80.0)
        assertTrue(view.yMax - view.yMin < full.yMax - full.yMin)
    }

    @Test
    fun theFullViewKeepsTheWholeChartsAxes() {
        val points = (0..4).map { Plotted(it * 10.0, 80f - it, regenerating = false, trip = 0, timestampMillis = 0) }
        val full = axisSpec(points)
        assertTrue(full.view(points, points.map { it.distance }, ChartViewport()) === full)
    }

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
    fun aGainBandJoinsMovingReadingsWithAGreenDotAndNoFallingRange() {
        fun at(
            i: Int,
            miles: Float?,
            regen: Boolean = false,
            trip: Int = 0,
            distance: Double = i * 10.0,
        ) = Plotted(distance, 60f, regenerating = regen, trip = trip, timestampMillis = 0, ratedMiles = miles)
        val points =
            listOf(
                at(0, 60f),
                at(1, 59f, regen = true), // regenerates, but fell
                at(2, 59f), // level after a green dot
                at(3, 59.5f), // rises: green
                at(4, 59.5f), // level after a green dot
                at(5, 59.5f), // level, neither end green
                at(6, 59.6f, distance = 50.0), // rises while standing
                at(7, 61f, trip = 1), // new trip
            )
        assertEquals(
            listOf(listOf(points[1], points[2]), listOf(points[2], points[3]), listOf(points[3], points[4])),
            gainBands(points),
        )
    }

    @Test
    fun aDotIsGreenWhenRegeneratingOrWhenTheRatedRangeRoseWhileMovingWithinTheTrip() {
        fun at(
            i: Int,
            miles: Float?,
            regen: Boolean = false,
            trip: Int = 0,
            distance: Double = i * 10.0,
        ) = Plotted(distance, 60f, regenerating = regen, trip = trip, timestampMillis = 0, ratedMiles = miles)
        val points =
            listOf(
                at(0, 60f),
                at(1, 59f), // drops
                at(2, 59.5f), // rises
                at(3, 59.5f), // level
                at(4, 58f, regen = true), // regenerates
                at(5, 70f, trip = 1), // first of a new trip
                at(6, 71f, trip = 1, distance = 50.0), // rises while standing
            )
        assertEquals(listOf(false, false, true, false, true, false, false), gainingDots(points))
    }
}
