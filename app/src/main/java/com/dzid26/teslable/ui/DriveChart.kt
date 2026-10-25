// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Where the chart's axes start, end and step, in miles driven and in the plotted unit. */
private class AxisSpec(
    val xStep: Double,
    val xMax: Double,
    val yStep: Double,
    val yMin: Double,
    val yMax: Double,
) {
    val range = paddedRange(yMin, yMax)
    val gridlines = generateSequence(yMin) { it + yStep }.takeWhile { it <= yMax + yStep / 2 }.toList()

    fun rangeProvider() = CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = xMax, minY = range.start, maxY = range.endInclusive)
}

private fun axisSpec(points: List<Plotted>): AxisSpec {
    val span = points.last().distance
    val xStep = niceStep(span / X_LABELS)
    val lowest = points.minOf { it.value.toDouble() } - Y_PAD
    val highest = points.maxOf { it.value.toDouble() } + Y_PAD
    val yStep = maxOf(niceStep((highest - lowest) / Y_LABELS), Y_MIN_STEP)
    val yMaxRaw = ceil(highest / yStep) * yStep
    return AxisSpec(
        xStep = xStep,
        xMax = ceil(span / xStep) * xStep,
        yStep = yStep,
        yMin = (floor(lowest / yStep) * yStep).coerceAtLeast(0.0),
        yMax = minOf(yMaxRaw, PERCENT_FULL),
    )
}

/**
 * One orange line per trip with its gradient, and, when any reading was
 * regenerating, a line-less series of those points in green drawn over them,
 * so the colour never depends on the odometer value a point happens to share.
 */
@Composable
private fun rememberDriveLines(
    tripCount: Int,
    hasRegen: Boolean,
    yMin: Double,
): LineCartesianLayer.LineProvider {
    val lineColor = driveChartColor
    val linePoint = rememberPoint(lineColor)
    val regenPoint = rememberPoint(chargingChartColor)
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
            pointProvider = remember(linePoint) { LineCartesianLayer.PointProvider.single(linePoint) },
        )
    val regenLine =
        LineCartesianLayer.rememberLine(
            fill = remember { LineCartesianLayer.LineFill.single(Fill(Color.Transparent)) },
            pointProvider = remember(regenPoint) { LineCartesianLayer.PointProvider.single(regenPoint) },
        )
    return remember(tripCount, hasRegen, driveLine, regenLine) {
        LineCartesianLayer.LineProvider.series(List(tripCount) { driveLine } + if (hasRegen) listOf(regenLine) else emptyList())
    }
}

@Composable
internal fun RangeChart(
    points: List<Plotted>,
    modifier: Modifier = Modifier,
) {
    val axes = remember(points) { axisSpec(points) }
    // A trip of one reading has no line to draw; its point still shows as a
    // regen point, so it is dropped from the trip series only.
    val trips = remember(points) { points.groupBy { it.trip }.values.filter { it.size >= 2 } }
    val regenPoints = remember(points) { points.filter { it.regenerating } }
    // Adjacent regenerating readings of one trip bound a stretch of regeneration.
    val regenBands =
        remember(points) {
            points
                .zipWithNext()
                .filter { (a, b) -> a.trip == b.trip && a.regenerating && b.regenerating }
                .map { (a, b) -> RegenBand(a.distance, a.value, b.distance, b.value) }
        }
    val unit = LocalDistanceUnit.current
    val distanceFormatter = remember(unit) { CartesianValueFormatter { _, value, _ -> formatDistance(value, unit) } }
    val yFormatter = remember { CartesianValueFormatter { _, value, _ -> formatWholePercent(value.toFloat()) } }
    val cursorDescriber =
        remember(unit) {
            PointDescriber { x, y -> "${formatPercent(y.toFloat())}% · ${formatDistance(x, unit, DISTANCE_DECIMALS)}" }
        }

    val pin = rememberCursorPin()

    ProvideVicoTheme(rememberM3VicoTheme()) {
        val cursor = rememberChartCursor(cursorDescriber)
        val chart =
            rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = rememberDriveLines(trips.size, regenPoints.isNotEmpty(), axes.yMin),
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
                            ScrubCursor(pin, cursor, points.map { it.distance to it.value.toDouble() }, driveChartColor),
                        )
                    },
                layerPadding = rememberEdgePadding(),
                getXStep = { _, _, _ -> axes.xStep },
            )
        CartesianChartHost(
            chart = chart,
            model = remember(trips, regenPoints) { rangeModel(trips, regenPoints) },
            modifier = modifier.scrubbing(pin),
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
        )
    }
}

/** One series per trip of the battery percent over distance driven, then, when any, one series of the regenerating points. */
private fun rangeModel(
    trips: List<List<Plotted>>,
    regenPoints: List<Plotted>,
): CartesianChartModel =
    CartesianChartModel(
        LineCartesianLayerModel.build {
            trips.forEach { trip -> series(x = trip.map { it.distance }, y = trip.map { it.value }) }
            if (regenPoints.isNotEmpty()) series(x = regenPoints.map { it.distance }, y = regenPoints.map { it.value })
        },
    )

/** A stretch of regeneration: two adjacent regenerating readings and the plotted value at each. */
private class RegenBand(
    val fromDistance: Double,
    val fromValue: Float,
    val toDistance: Double,
    val toValue: Float,
)

/** Green gradient between adjacent regenerating readings, filled from the battery curve down to the axis. */
private class RegenBands(
    private val bands: List<RegenBand>,
    private val color: Color,
) : Decoration {
    override fun drawUnderLayers(context: CartesianDrawingContext) {
        with(context) {
            fun xOf(distance: Double): Float = canvasX(distance)

            fun yOf(value: Float): Float = canvasY(value.toDouble())

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
            bands.forEach { band ->
                val path =
                    Path().apply {
                        moveTo(xOf(band.fromDistance), layerBounds.bottom)
                        lineTo(xOf(band.fromDistance), yOf(band.fromValue))
                        lineTo(xOf(band.toDistance), yOf(band.toValue))
                        lineTo(xOf(band.toDistance), layerBounds.bottom)
                        close()
                    }
                canvas.drawPath(path, paint)
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

/** 1, 2 or 5 times a power of ten: the axis step nearest above [raw]. */
private fun niceStep(raw: Double): Double {
    if (raw <= 0.0) return 1.0
    val magnitude = DECADE.pow(floor(log10(raw)))
    return listOf(1.0, 2.0, HALF_DECADE, DECADE).first { it * magnitude >= raw } * magnitude
}

private const val HALF_DECADE = 5.0
private const val DECADE = 10.0

private const val DRIVE_ORANGE_ARGB = 0xFFF28C28

internal val driveChartColor = Color(DRIVE_ORANGE_ARGB)

private const val REGEN_TOP_ALPHA = 0.45f
private const val REGEN_BOTTOM_ALPHA = 0.05f
private const val X_LABELS = 4
private const val Y_LABELS = 4
private const val Y_MIN_STEP = 1.0
private const val Y_PAD = 1.0
private const val PERCENT_FULL = 100.0

/** The canvas x of a data x on the first layer, for decorations that draw between data points. */
internal fun CartesianDrawingContext.canvasX(x: Double): Float =
    layerBounds.left + layerDimensions.startPadding - scroll + layerDimensions.xSpacing * ((x - ranges.minX) / ranges.xStep).toFloat()

/** The canvas y of a data y on the first layer's y range. */
internal fun CartesianDrawingContext.canvasY(y: Double): Float {
    val yRange = ranges.getYRange(null)
    return layerBounds.bottom - ((y - yRange.minY) / yRange.length).toFloat() * layerBounds.height
}

/** The data x on the first layer at a canvas x: the inverse of [canvasX]. */
internal fun CartesianDrawingContext.dataX(canvasX: Float): Double =
    ranges.minX + (canvasX - (layerBounds.left + layerDimensions.startPadding - scroll)) / layerDimensions.xSpacing * ranges.xStep
