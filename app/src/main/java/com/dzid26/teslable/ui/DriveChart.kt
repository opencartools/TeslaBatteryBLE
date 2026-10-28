// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.core.units.DistanceUnit
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme

/**
 * One plain orange line per trip with its gradient. The readings are drawn on top of it by [ReadingDots], so a
 * regenerating reading is green in its own time order. A regenerating reading that held the rated range is the
 * green gradient under the lines.
 */
@Composable
private fun rememberDriveLines(
    tripCount: Int,
    yMin: Double,
): LineCartesianLayer.LineProvider {
    val lineColor = driveChartColor
    val driveLine =
        LineCartesianLayer.rememberLine(
            fill = remember(lineColor) { LineCartesianLayer.LineFill.single(Fill(lineColor)) },
            areaFill =
                remember(lineColor, yMin) {
                    LineCartesianLayer.AreaFill.single(
                        Fill(Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.3f), Color.Transparent))),
                        splitY = { yMin },
                    )
                },
            pointProvider = null,
        )
    return remember(tripCount, driveLine) { LineCartesianLayer.LineProvider.series(List(tripCount) { driveLine }) }
}

/** Where the drive chart's cursor starts; set by the Paparazzi snapshots, which cannot touch the chart. */
internal val LocalDriveCursor = staticCompositionLocalOf<CursorPin?> { null }

/** The cursor text: percent, distance driven and the date and time of the reading the cursor stands on. */
@Composable
private fun rememberDriveDescriber(
    points: List<Plotted>,
    unit: DistanceUnit,
    pin: CursorPin,
): PointDescriber {
    val is24Hour = LocalIs24Hour.current ?: DateFormat.is24HourFormat(LocalContext.current)
    return remember(points, unit, is24Hour, pin) {
        PointDescriber { x, y ->
            // Readings can share a distance, so the time comes from the point the cursor snapped to, not from x.
            val millis = points[pin.shownIndex].timestampMillis
            driveCursorText(
                percent = y.toFloat(),
                distance = formatDistance(x, unit, DISTANCE_DECIMALS),
                time = formatTime(millis, showDate = true, is24Hour = is24Hour),
            )
        }
    }
}

/** "71.4% · 12.3mi · Oct 26 14:05". */
internal fun driveCursorText(
    percent: Float,
    distance: String,
    time: String,
): String = "${formatPercent(percent)}% · $distance · $time"

@Composable
internal fun RangeChart(
    points: List<Plotted>,
    modifier: Modifier = Modifier,
    pin: CursorPin = LocalDriveCursor.current ?: rememberCursorPin(),
) {
    val axes = remember(points) { axisSpec(points) }
    val trips = remember(points) { points.groupBy { it.trip }.values.toList() }
    // The gradient marks a regen reading with no drop in rated range from the previous or to the next reading;
    // at one odometer value it has no width.
    val regenBands = remember(points) { regenHolds(points).filter { it.first().distance != it.last().distance } }
    val unit = LocalDistanceUnit.current
    val distanceFormatter = remember(unit) { CartesianValueFormatter { _, value, _ -> formatDistance(value, unit) } }
    val yFormatter = remember { CartesianValueFormatter { _, value, _ -> formatWholePercent(value.toFloat()) } }
    val cursorDescriber = rememberDriveDescriber(points, unit, pin)

    ProvideVicoTheme(rememberM3VicoTheme()) {
        val cursor = rememberChartCursor(cursorDescriber)
        val chart =
            rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = rememberDriveLines(trips.size, axes.yMin),
                    rangeProvider =
                        remember(axes) { axes.rangeProvider() },
                ),
                startAxis =
                    VerticalAxis.rememberStart(
                        valueFormatter = yFormatter,
                        itemPlacer = remember(axes) { labelsAt(axes.gridlines) },
                    ),
                bottomAxis =
                    HorizontalAxis.rememberBottom(
                        valueFormatter = distanceFormatter,
                        itemPlacer = remember { InteriorLabels(HorizontalAxis.ItemPlacer.aligned(addExtremeLabelPadding = false)) },
                    ),
                decorations =
                    remember(regenBands, points, pin, cursor) {
                        listOf(
                            RegenBands(regenBands, chargingChartColor),
                            ReadingDots(points, driveChartColor, chargingChartColor),
                            ScrubCursor(pin, cursor, points.map { it.distance to it.value.toDouble() }, driveChartColor),
                        )
                    },
                layerPadding = rememberEdgePadding(),
                getXStep = { _, _, _ -> axes.xStep },
            )
        CartesianChartHost(
            chart = chart,
            model = remember(trips) { rangeModel(trips) },
            modifier = modifier.scrubbing(pin),
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
        )
    }
}

/** One series per trip of the battery percent over distance driven. */
private fun rangeModel(trips: List<List<Plotted>>): CartesianChartModel =
    CartesianChartModel(
        LineCartesianLayerModel.build {
            trips.forEach { trip -> series(x = trip.map { it.distance }, y = trip.map { it.value }) }
        },
    )

/**
 * Green gradient under each regen pair that held the rated range, filled from the battery curve down to the
 * axis, at its true width.
 */
private class RegenBands(
    private val gains: List<List<Plotted>>,
    private val color: Color,
) : Decoration {
    override fun drawUnderLayers(context: CartesianDrawingContext) {
        with(context) {
            // Hoisted: inside Paint().apply, `color` would resolve to Paint.color.
            val top = color.copy(alpha = REGEN_TOP_ALPHA)
            val bottom = color.copy(alpha = REGEN_BOTTOM_ALPHA)
            val paint =
                Paint().apply {
                    shader =
                        LinearGradientShader(
                            from = Offset(0f, layerBounds.top),
                            to = Offset(0f, layerBounds.bottom),
                            colors = listOf(top, bottom),
                        )
                }
            gains.forEach { run ->
                // Left to right on screen, whichever way the layout runs.
                val edge = run.map { Offset(canvasX(it.distance), canvasY(it.value.toDouble())) }.sortedBy { it.x }
                val path =
                    Path().apply {
                        moveTo(edge.first().x, layerBounds.bottom)
                        edge.forEach { lineTo(it.x, it.y) }
                        lineTo(edge.last().x, layerBounds.bottom)
                        close()
                    }
                canvas.drawPath(path, paint)
            }
        }
    }
}

/**
 * Every reading as a dot in time order, green when it was regenerating and orange otherwise. Drawn from the readings
 * themselves rather than by looking their position up, so two readings at one spot can never swap colours.
 */
private class ReadingDots(
    private val points: List<Plotted>,
    private val color: Color,
    private val regenColor: Color,
) : Decoration {
    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            val radius = DOT_SIZE.pixels / 2
            points.forEach {
                canvas.drawCircle(
                    Offset(canvasX(it.distance), canvasY(it.value.toDouble())),
                    radius,
                    Paint().apply { this.color = if (it.regenerating) regenColor else color },
                )
            }
        }
    }
}

@Composable
internal fun rememberPoint(
    color: Color,
    size: Dp = 5.dp,
): LineCartesianLayer.Point {
    val component = rememberShapeComponent(fill = Fill(color), shape = CircleShape)
    return remember(component) { LineCartesianLayer.Point(component, size = size) }
}

private val DOT_SIZE = 5.dp

private const val DRIVE_ORANGE_ARGB = 0xFFF28C28

internal val driveChartColor = Color(DRIVE_ORANGE_ARGB)

private const val REGEN_TOP_ALPHA = 0.45f
private const val REGEN_BOTTOM_ALPHA = 0.05f

/**
 * The canvas x of a data x on the first layer, for decorations that draw between data points. Vico
 * lays the layer out from the right edge in a right-to-left layout, so the offset runs from there.
 */
internal fun CartesianDrawingContext.canvasX(x: Double): Float = layerStartEdge() + layoutDirectionMultiplier * layerOffset(x)

/** The canvas y of a data y on the first layer's y range. */
internal fun CartesianDrawingContext.canvasY(y: Double): Float {
    val yRange = ranges.getYRange(null)
    return layerBounds.bottom - ((y - yRange.minY) / yRange.length).toFloat() * layerBounds.height
}

/** The data x on the first layer at a canvas x: the inverse of [canvasX]. */
internal fun CartesianDrawingContext.dataX(canvasX: Float): Double =
    ranges.minX + (layoutDirectionMultiplier * (canvasX - layerStartEdge()) - (layerDimensions.startPadding - scroll)) /
        layerDimensions.xSpacing * ranges.xStep

private fun CartesianDrawingContext.layerStartEdge(): Float = if (isLtr) layerBounds.left else layerBounds.right

private fun CartesianDrawingContext.layerOffset(x: Double): Float =
    layerDimensions.startPadding - scroll + layerDimensions.xSpacing * ((x - ranges.minX) / ranges.xStep).toFloat()
