// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.ChargeProjection
import com.dzid26.teslable.core.history.DischargeProjection
import com.dzid26.teslable.core.history.FullRangeScale
import com.dzid26.teslable.core.history.TimeSpan
import com.dzid26.teslable.core.history.soc
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import java.text.DecimalFormatSymbols
import kotlin.math.abs
import kotlin.math.roundToLong

/** What a series of the battery chart draws; the line provider maps each kind to its look. */
internal enum class SeriesKind { MEASURED, MEASURED_CHARGING, DOTS, DOTS_CHARGING, CHARGE_PROJECTION, DISCHARGE_PROJECTION }

/** What the chart plots: the readings, the projections, the scale that turns them into percent, and the shaded bands. */
internal data class ChartData(
    val samples: List<BatterySample>,
    val charge: ChargeProjection?,
    val discharge: DischargeProjection?,
    val scale: FullRangeScale?,
    // The stretches each kind shades behind the plot, in the kind's paint order (parked first).
    val bands: Map<BandKind, List<TimeSpan>> = emptyMap(),
    // The parts of each kind's bands the log lost: striped rather than shaded flat.
    val unseen: Map<BandKind, List<TimeSpan>> = emptyMap(),
)

/** The time window and how its times are written. */
internal data class TimeAxis(
    val windowStart: Long,
    val windowEnd: Long,
    val showDate: Boolean,
    val is24Hour: Boolean,
)

/** One series of the model: x in hours since the window start, y in percent. */
internal class SeriesSpec(
    val kind: SeriesKind,
    val xs: List<Double>,
    val ys: List<Double>,
) {
    /** The percents within [shown], with the point just before and just after it; [xs] ascending. */
    fun ysAround(shown: ClosedFloatingPointRange<Double>): List<Double> {
        val first = xs.insertionPoint(shown.start, after = false)
        val last = xs.insertionPoint(shown.endInclusive, after = true) - 1
        val from = (first - 1).coerceAtLeast(0)
        val to = (last + 1).coerceAtMost(xs.lastIndex)
        return if (from > to) emptyList() else ys.subList(from, to + 1)
    }

    /** Vico wants ascending, unique x; the readings carry no such promise, so sort them and keep the last of equal times. */
    fun normalized(): SeriesSpec {
        val pairs = xs.zip(ys).sortedBy { it.first }.distinctBy { it.first }
        return SeriesSpec(kind, pairs.map { it.first }, pairs.map { it.second })
    }
}

/** A projected point: the time it refers to and the percent there. */
internal class TimePoint(
    val millis: Long,
    val percent: Float,
)

/** A wedge between three data points, drawn under the lines. */
internal class Beam(
    val xs: List<Double>,
    val ys: List<Double>,
    val color: Color,
)

/**
 * Battery percent over time: the measured line with a dot per reading, the projections and
 * their beams, the shaded power-down stretches, and a cursor under the finger.
 */
@Composable
internal fun BatteryChart(
    data: ChartData,
    axis: TimeAxis,
    modifier: Modifier = Modifier,
    viewport: ChartViewport = remember { ChartViewport() },
) {
    val samples = data.samples
    val charge = data.charge
    val scale = data.scale
    val lineColor = MaterialTheme.colorScheme.primary
    // Keyed on the minute: the card passes a fresh wall-clock read on every recomposition, which
    // would rebuild the model and drop a cursor mid-press.
    val window =
        remember(axis.windowStart / MILLIS_PER_MINUTE, axis.windowEnd / MILLIS_PER_MINUTE) { Window(axis.windowStart, axis.windowEnd) }

    val shape =
        remember(samples, charge, data.discharge, scale, data.bands, data.unseen, window, lineColor) {
            batteryShape(data, window, lineColor, chargingChartColor)
        }
    val lines = rememberBatteryLines(lineColor, chargingChartColor, parkedMuted = charge != null)
    val icons = remember { BandIcons() }
    val historyBands = rememberHistoryBands(shape.bands, icons)
    val timeFormatter = rememberTimeFormatter(window, axis.showDate, axis.is24Hour)
    val view = remember(shape, window.spanHours, viewport.start, viewport.end) { shape.view(window.spanHours, viewport) }
    val xStep = (view.maxX - view.minX) / X_LABEL_STEPS
    val clock =
        remember(window, view.maxX - view.minX, viewport.isFull) {
            if (viewport.isFull) null else clockSteps(window.start, view.maxX - view.minX)
        }
    val description = remember(samples, scale) { chartDescription(samples, scale) }

    val pin = rememberCursorPin()

    ProvideVicoTheme(rememberM3VicoTheme()) {
        val cursor = rememberCursorMarker(window, axis, samples, scale, pin)
        val chart =
            rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider =
                        remember(
                            shape.specs,
                            lines,
                        ) { LineCartesianLayer.LineProvider.series(shape.specs.map { lines.getValue(it.kind) }) },
                    rangeProvider =
                        remember(view) { view.rangeProvider() },
                ),
                startAxis = rememberPercentAxis(view.gridlines),
                bottomAxis = rememberTimeAxis(timeFormatter, clock),
                decorations =
                    remember(shape.specs, shape.beams, pin, cursor, lineColor, viewport, historyBands) {
                        batteryDecorations(ChartLayers(shape, pin, cursor, lineColor, viewport, historyBands))
                    },
                layerPadding = rememberEdgePadding(),
                getXStep = { _, _, _ -> xStep },
            )
        BandIconHost(modifier, icons) {
            CartesianChartHost(
                chart = chart,
                model = remember(shape.specs) { batteryModel(shape.specs) },
                modifier =
                    Modifier
                        .matchParentSize()
                        .scrubbing(pin, viewport, LocalHapticFeedback.current, icons)
                        .semantics { contentDescription = description },
                scrollState = rememberVicoScrollState(scrollEnabled = false),
                zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
            )
        }
    }
}

@Composable
private fun rememberTimeFormatter(
    window: Window,
    showDate: Boolean,
    is24Hour: Boolean,
) = remember(window, showDate, is24Hour) {
    CartesianValueFormatter { _, value, _ ->
        formatTime(window.start + (value * MILLIS_PER_HOUR).roundToLong(), showDate, is24Hour)
    }
}

@Composable
private fun rememberCursorMarker(
    window: Window,
    axis: TimeAxis,
    samples: List<BatterySample>,
    scale: FullRangeScale?,
    pin: CursorPin,
): DefaultCartesianMarker {
    val unit = LocalDistanceUnit.current
    val separator = remember { DecimalFormatSymbols.getInstance().decimalSeparator }
    val describer =
        remember(window, axis, samples, scale, unit, separator) {
            val lastMeasuredMillis = samples.last().timestampMillis
            PointDescriber { x, y ->
                val millis = window.start + (x * MILLIS_PER_HOUR).roundToLong()
                val projected = millis > lastMeasuredMillis
                // A measured point has its own rated range; only a projection, which exists
                // as a percent alone, goes through the fitted line (unknown until it pins).
                val range =
                    if (projected) {
                        scale?.takeIf { it.pinned }?.ratedMilesAt(y.toFloat())
                    } else {
                        samples.minBy { abs(it.timestampMillis - millis) }.ratedRangeMiles
                    }
                val rangeText = range?.let { unit.format(it, DISTANCE_DECIMALS, separator) }
                cursorText(y.toFloat(), projected, formatTime(millis, axis.showDate, axis.is24Hour), rangeText)
            }
        }
    val span =
        remember(window) {
            SpanDescriber { from, to ->
                fun at(point: Pair<Double, Double>) = window.start + (point.first * MILLIS_PER_HOUR).roundToLong() to point.second.toFloat()
                timeSpanText(at(from), at(to))
            }
        }
    return rememberChartCursor(describer, span, pin)
}

/** What TalkBack reads for the chart: the span of the measured levels. */
private fun chartDescription(
    samples: List<BatterySample>,
    scale: FullRangeScale?,
): String {
    val socs = samples.map { it.soc(scale) }
    return "Battery history chart, ${formatPercent(socs.min())}% to ${formatPercent(socs.max())}%"
}

internal class BatteryShape(
    val specs: List<SeriesSpec>,
    val beams: List<Beam>,
    val bands: List<Band>,
    val yMin: Double,
    val yMax: Double,
) {
    /** The plotted range: the axis bounds with headroom at 0% and 100%. */
    val range = paddedRange(yMin, yMax)

    /** Bottom, middle and top gridlines, at the axis bounds rather than the padded range. */
    val gridlines = listOf(yMin, (yMin + yMax) / 2, yMax)

    /**
     * What [viewport] shows of the window, [spanHours] wide: its hours, and a percent axis fitted to the points on
     * screen (with the nearest point past each edge, which the line runs to). At full view, the whole chart's axis.
     */
    fun view(
        spanHours: Double,
        viewport: ChartViewport,
    ): BatteryView {
        val shown = viewport.visible(0.0, spanHours)
        val ys = if (viewport.isFull) emptyList() else specs.flatMap { it.ysAround(shown) }
        if (ys.isEmpty()) return BatteryView(shown.start, shown.endInclusive, range, gridlines)
        val (low, high) = roundedBounds(ys.min().toFloat(), ys.max().toFloat())
        val fitted = BatteryShape(specs, beams, bands, low.toDouble(), high.toDouble())
        return BatteryView(shown.start, shown.endInclusive, fitted.range, fitted.gridlines)
    }
}

/** The x range on screen, in hours since the window start, and the percent axis fitted to it. */
internal data class BatteryView(
    val minX: Double,
    val maxX: Double,
    val range: ClosedFloatingPointRange<Double>,
    val gridlines: List<Double>,
) {
    fun rangeProvider() = CartesianLayerRangeProvider.fixed(minX = minX, maxX = maxX, minY = range.start, maxY = range.endInclusive)
}

/** Where a time falls on the x axis: hours since the window start, kept inside the window. */
internal class Window(
    val start: Long,
    val end: Long,
) {
    val spanHours = (end - start).coerceAtLeast(1L).toDouble() / MILLIS_PER_HOUR

    fun hours(millis: Long): Double = ((millis - start).toDouble() / MILLIS_PER_HOUR).coerceIn(0.0, spanHours)
}

internal fun batteryShape(
    data: ChartData,
    window: Window,
    lineColor: Color,
    chargingColor: Color,
): BatteryShape {
    val samples = data.samples
    val charge = data.charge
    val discharge = data.discharge
    val scale = data.scale
    val socs = samples.map { it.soc(scale) }
    val (yMin, yMax) = yBounds(socs, charge, discharge)
    val xs = samples.map { window.hours(it.timestampMillis) }
    val ys = socs.map { it.toDouble() }
    val projections =
        listOfNotNull(
            charge?.let { chargeProjected(it, window, chargingColor) },
            discharge?.let { dischargeProjected(it, window, lineColor, muted = charge != null) },
        )
    return BatteryShape(
        specs = (measuredSpecs(samples, xs, ys) + projections.map { it.spec }).map { it.normalized() },
        beams = projections.mapNotNull { it.beam },
        bands =
            BandKind.entries.flatMap { kind ->
                data.bands[kind].orEmpty().mapNotNull { span ->
                    // Clamped to the window: a span that reaches past its edge is cut there, and one off it draws nothing.
                    val start = window.hours(span.startMillis)
                    val end = window.hours(span.endMillis)
                    if (end <= start) return@mapNotNull null
                    // The band owns its holes, so the hatch can never land on another kind's band.
                    val holes =
                        data.unseen[kind].orEmpty().mapNotNull { hole ->
                            val holeStart = window.hours(hole.startMillis).coerceIn(start, end)
                            val holeEnd = window.hours(hole.endMillis).coerceIn(start, end)
                            if (holeEnd > holeStart) holeStart..holeEnd else null
                        }
                    Band(start, end, kind, holes)
                }
            },
        yMin = yMin.toDouble(),
        yMax = yMax.toDouble(),
    )
}

/** The axis range in whole multiples of [AXIS_ROUND] around the readings and the projections. */
internal fun yBounds(
    socs: List<Float>,
    charge: ChargeProjection?,
    discharge: DischargeProjection?,
): Pair<Int, Int> {
    val minPercent = minOf(socs.min(), discharge?.let { it.lowSoc ?: it.projectedSoc } ?: PERCENT_FULL)
    val maxPercent = maxOf(socs.max(), (charge?.targetPercent ?: 0).toFloat())
    return roundedBounds(minPercent, maxPercent)
}

/** [minPercent] to [maxPercent] widened by a margin and rounded out to whole multiples of [AXIS_ROUND]. */
internal fun roundedBounds(
    minPercent: Float,
    maxPercent: Float,
): Pair<Int, Int> {
    val yMin = ((minPercent - Y_MARGIN).toInt().coerceAtLeast(0) / AXIS_ROUND) * AXIS_ROUND
    val yMax = (((maxPercent + Y_MARGIN).toInt().coerceAtMost(PERCENT_FULL.toInt()) + AXIS_ROUND - 1) / AXIS_ROUND) * AXIS_ROUND
    val base =
        if (yMax > yMin) {
            yMin to yMax
        } else {
            val lower = (yMin - AXIS_ROUND).coerceAtLeast(0)
            lower to (lower + AXIS_ROUND).coerceAtMost(PERCENT_FULL.toInt())
        }
    return evenRange(base)
}

/** Widens the range by one step when needed so its middle gridline lands on a multiple of [AXIS_ROUND]. */
private fun evenRange(range: Pair<Int, Int>): Pair<Int, Int> {
    val (low, high) = range
    return when {
        (high - low) % (2 * AXIS_ROUND) == 0 -> range
        high + AXIS_ROUND <= PERCENT_FULL.toInt() -> low to high + AXIS_ROUND
        else -> low - AXIS_ROUND to high
    }
}

/** The coloured line runs, then one dot series per charging state. */
private fun measuredSpecs(
    samples: List<BatterySample>,
    xs: List<Double>,
    ys: List<Double>,
): List<SeriesSpec> {
    val specs = mutableListOf<SeriesSpec>()
    // A segment takes the colour of the sample it ends at, so each run starts at the sample before it.
    var index = 1
    while (index < samples.size) {
        val charging = samples[index].isCharging
        var end = index
        while (end + 1 < samples.size && samples[end + 1].isCharging == charging) end++
        val kind = if (charging) SeriesKind.MEASURED_CHARGING else SeriesKind.MEASURED
        specs += SeriesSpec(kind, xs.slice(index - 1..end), ys.slice(index - 1..end))
        index = end + 1
    }
    // One dot per measurement, no decimation: dense samples overlap into a thick band.
    listOf(false to SeriesKind.DOTS, true to SeriesKind.DOTS_CHARGING).forEach { (charging, kind) ->
        val picked = samples.indices.filter { samples[it].isCharging == charging }
        if (picked.isNotEmpty()) specs += SeriesSpec(kind, picked.map { xs[it] }, picked.map { ys[it] })
    }
    return specs
}

private fun batteryModel(specs: List<SeriesSpec>): CartesianChartModel =
    CartesianChartModel(
        LineCartesianLayerModel.build {
            specs.forEach { series(x = it.xs, y = it.ys) }
        },
    )

private const val MILLIS_PER_HOUR = 3_600_000.0
private const val X_LABEL_STEPS = 4

private const val MILLIS_PER_MINUTE = 60_000L
private const val PERCENT_FULL = 100f
private const val Y_MARGIN = 2
private const val AXIS_ROUND = 5

/** The cursor label: "Projected " only past the last measurement, then the percent, the range at that percent when known, and the time. */
internal fun cursorText(
    percent: Float,
    projected: Boolean,
    time: String,
    range: String? = null,
): String {
    val level = "${formatPercent(percent)}%" + range?.let { " ($it)" }.orEmpty()
    return "${if (projected) "Projected " else ""}$level · $time"
}
