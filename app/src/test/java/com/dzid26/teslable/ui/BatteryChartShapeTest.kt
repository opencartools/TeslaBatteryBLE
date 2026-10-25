// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.ui.graphics.Color
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.ChargeProjection
import com.dzid26.teslable.protocol.ChargingStateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryChartShapeTest {
    private val hour = 3_600_000L

    private fun sample(
        hours: Double,
        percent: Int,
        charging: Boolean = false,
    ) = BatterySample(
        timestampMillis = (hours * hour).toLong(),
        batteryLevel = percent,
        chargingState = if (charging) ChargingStateKind.Charging else ChargingStateKind.Disconnected,
        chargeLimit = null,
    )

    private fun shape(
        samples: List<BatterySample>,
        charge: ChargeProjection? = null,
        windowEnd: Long = 10 * hour,
    ) = batteryShape(ChartData(samples, charge, null, null), Window(0L, windowEnd), Color.Blue, Color.Green)

    @Test
    fun runsTakeTheColourOfTheirEndingSample() {
        val kinds =
            shape(listOf(sample(0.0, 50), sample(1.0, 51), sample(2.0, 52, true), sample(3.0, 53, true), sample(4.0, 52)))
                .specs
                .map { it.kind }
        assertEquals(
            listOf(SeriesKind.MEASURED, SeriesKind.MEASURED_CHARGING, SeriesKind.MEASURED, SeriesKind.DOTS, SeriesKind.DOTS_CHARGING),
            kinds,
        )
    }

    @Test
    fun aLoneSampleIsOnlyADot() {
        assertEquals(listOf(SeriesKind.DOTS), shape(listOf(sample(1.0, 60))).specs.map { it.kind })
    }

    @Test
    fun unsortedAndRepeatedTimesAreSortedAndDeduplicated() {
        val spec =
            shape(listOf(sample(2.0, 52), sample(1.0, 51), sample(1.0, 55), sample(3.0, 53))).specs.first {
                it.kind ==
                    SeriesKind.DOTS
            }
        assertEquals(listOf(1.0, 2.0, 3.0), spec.xs)
        assertEquals(listOf(51.0, 52.0, 53.0), spec.ys)
    }

    @Test
    fun yRangeRoundsOutToFivesWithAMarginAndAWholeMiddle() {
        val shape = shape(listOf(sample(0.0, 62), sample(1.0, 71)))
        assertEquals(60.0, shape.yMin, 0.0)
        assertEquals(80.0, shape.yMax, 0.0)
    }

    @Test
    fun theRangeHasHeadroomAt100AndGridlinesStayOnTheBounds() {
        val shape = shape(listOf(sample(0.0, 96), sample(1.0, 100)))
        assertEquals(100.0, shape.yMax, 0.0)
        assertEquals(101.0, shape.range.endInclusive, 0.0)
        assertEquals(shape.yMin, shape.range.start, 0.0)
        assertEquals(listOf(shape.yMin, (shape.yMin + 100.0) / 2, 100.0), shape.gridlines)
    }

    @Test
    fun aFlatRangeStillSpansFivePoints() {
        val (low, high) = yBounds(listOf(100f), null, null)
        assertTrue(high > low)
    }

    @Test
    fun aCompletionPastTheEdgeKeepsItsSlope() {
        val from = sample(0.0, 50, true)
        val projection = ChargeProjection(from = from, targetPercent = 80, completionMillis = 20 * hour)
        val end = chargeEnd(projection, 10 * hour, projection.completionMillis)
        assertEquals(10 * hour, end.millis)
        assertEquals(65f, end.percent, 0.001f)
        assertEquals(80f, chargeEnd(projection, 10 * hour, 5 * hour).percent, 0f)
    }

    @Test
    fun theChargeProjectionHasPointsAlongIt() {
        val projection = ChargeProjection(from = sample(0.0, 50, true), targetPercent = 80, completionMillis = 5 * hour)
        val spec = shape(listOf(sample(0.0, 50, true)), projection).specs.first { it.kind == SeriesKind.CHARGE_PROJECTION }
        assertTrue(spec.xs.size > 2)
        assertEquals(50.0, spec.ys.first(), 0.0)
        assertEquals(80.0, spec.ys.last(), 0.0)
        assertNull(shape(listOf(sample(0.0, 50))).beams.firstOrNull())
    }

    @Test
    fun cursorTextMarksProjectedPoints() {
        assertEquals("Projected 83.2% · 14:00", cursorText(83.2f, true, "14:00"))
        assertEquals("64% · 09:15", cursorText(64f, false, "09:15"))
        assertEquals("Projected 80% (200 mi) · 14:00", cursorText(80f, true, "14:00", "200 mi"))
    }
}
