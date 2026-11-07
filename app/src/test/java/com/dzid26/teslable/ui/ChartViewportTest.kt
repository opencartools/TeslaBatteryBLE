// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartViewportTest {
    @Test
    fun spreadingTheFingersZoomsInAroundThem() {
        // Fingers in the middle of the whole chart spread to twice their distance: half the chart, centred.
        val (start, end) = panZoom(0.0, 1.0, from = 0.5, to = 0.5, scale = 2.0)
        assertEquals(0.25, start, EPS)
        assertEquals(0.75, end, EPS)
    }

    @Test
    fun thePointUnderTheFingersStaysUnderThem() {
        // Zooming in at a quarter of the way across keeps that point a quarter of the way across.
        val (start, end) = panZoom(0.0, 1.0, from = 0.25, to = 0.25, scale = 4.0)
        assertEquals(0.25, start + 0.25 * (end - start), EPS)
    }

    @Test
    fun movingTheFingersTogetherPans() {
        // Half the chart on screen; the fingers move a tenth of the view to the right, so the view moves left.
        val (start, end) = panZoom(0.4, 0.9, from = 0.5, to = 0.6, scale = 1.0)
        assertEquals(0.35, start, EPS)
        assertEquals(0.85, end, EPS)
    }

    @Test
    fun theViewStaysInsideTheChart() {
        val (start, end) = panZoom(0.0, 0.5, from = 0.5, to = 0.9, scale = 1.0)
        assertEquals(0.0, start, EPS)
        assertEquals(0.5, end, EPS)
    }

    @Test
    fun pinchingOutStopsAtTheWholeChart() {
        val (start, end) = panZoom(0.2, 0.4, from = 0.5, to = 0.5, scale = 0.01)
        assertEquals(0.0, start, EPS)
        assertEquals(1.0, end, EPS)
    }

    @Test
    fun zoomingInStopsAtTheNarrowestView() {
        val (start, end) = panZoom(0.0, 1.0, from = 0.5, to = 0.5, scale = 1000.0)
        assertEquals(MIN_VIEW_SPAN, end - start, EPS)
    }

    @Test
    fun aViewportStartsWhole() {
        val viewport = ChartViewport()
        assertTrue(viewport.isFull)
        assertEquals(2.0..10.0, viewport.visible(2.0, 10.0))
    }

    @Test
    fun fingersStackedVerticallyOnlyPan() {
        val viewport = ChartViewport().apply { layerWidth = 100f }
        // Both fingers at nearly the same x: the change in their distance is no zoom.
        viewport.transform(previousX = 50f, x = 50f, previousSpan = 2f, span = 6f, minFingerSpan = 8f)
        assertTrue(viewport.isFull)
    }

    @Test
    fun aRightToLeftChartRunsFromTheRightEdge() {
        val viewport =
            ChartViewport().apply {
                layerWidth = 100f
                ltr = false
            }
        // Spreading at the right quarter of a mirrored chart zooms in near its start.
        viewport.transform(previousX = 75f, x = 75f, previousSpan = 20f, span = 40f, minFingerSpan = 8f)
        assertEquals(0.125, viewport.start, EPS)
        assertEquals(0.625, viewport.end, EPS)
    }

    @Test
    fun roundStepsAreTheWholeStepsInside() {
        assertEquals(listOf(10.0, 15.0, 20.0), roundSteps(7.3..21.0, 5.0))
        // Edges that land on a step get no label.
        assertEquals(listOf(5.0, 10.0), roundSteps(0.0..15.0, 5.0))
        // Gridlines keep them.
        assertEquals(listOf(0.0, 5.0, 10.0, 15.0), roundSteps(0.0..15.0, 5.0, withEdges = true))
        assertEquals(listOf(10.0, 15.0, 20.0), roundSteps(7.3..21.0, 5.0, withEdges = true))
    }

    @Test
    fun theInsertionPointIsABinarySearch() {
        val xs = listOf(1.0, 2.0, 2.0, 4.0)
        assertEquals(1, xs.insertionPoint(2.0, after = false))
        assertEquals(3, xs.insertionPoint(2.0, after = true))
        assertEquals(4, xs.insertionPoint(9.0, after = false))
    }

    private companion object {
        const val EPS = 1e-9
    }
}
