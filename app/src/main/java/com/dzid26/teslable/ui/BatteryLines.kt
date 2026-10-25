// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisTickComponent
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.layer.CartesianLayerDimensions
import com.patrykandpatrick.vico.compose.cartesian.layer.CartesianLayerPadding
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore

@Composable
internal fun rememberBatteryLines(
    lineColor: Color,
    chargingColor: Color,
    parkedMuted: Boolean,
): Map<SeriesKind, LineCartesianLayer.Line> {
    val measured = lineFor(lineColor)
    val measuredCharging = lineFor(chargingColor)
    val dots = dotsLine(lineColor)
    val dotsCharging = dotsLine(chargingColor)
    val chargeProjection = dashedLine(chargingColor.copy(alpha = CHARGE_LINE_ALPHA))
    val dischargeProjection =
        dashedLine(lineColor.copy(alpha = if (parkedMuted) DISCHARGE_LINE_MUTED_ALPHA else DISCHARGE_LINE_ALPHA))
    return remember(measured, measuredCharging, dots, dotsCharging, chargeProjection, dischargeProjection) {
        mapOf(
            SeriesKind.MEASURED to measured,
            SeriesKind.MEASURED_CHARGING to measuredCharging,
            SeriesKind.DOTS to dots,
            SeriesKind.DOTS_CHARGING to dotsCharging,
            SeriesKind.CHARGE_PROJECTION to chargeProjection,
            SeriesKind.DISCHARGE_PROJECTION to dischargeProjection,
        )
    }
}

@Composable
private fun lineFor(color: Color): LineCartesianLayer.Line =
    LineCartesianLayer.rememberLine(
        fill = remember(color) { LineCartesianLayer.LineFill.single(Fill(color)) },
        stroke = remember { LineCartesianLayer.LineStroke.Continuous(thickness = lineWidth, cap = StrokeCap.Round) },
    )

/** A line-less series: only its points show, one per measurement. */
@Composable
private fun dotsLine(color: Color): LineCartesianLayer.Line {
    val point = rememberPoint(color, size = dotSize)
    return LineCartesianLayer.rememberLine(
        fill = remember { LineCartesianLayer.LineFill.single(Fill(Color.Transparent)) },
        pointProvider = remember(point) { LineCartesianLayer.PointProvider.single(point) },
    )
}

@Composable
private fun dashedLine(color: Color): LineCartesianLayer.Line =
    LineCartesianLayer.rememberLine(
        fill = remember(color) { LineCartesianLayer.LineFill.single(Fill(color)) },
        stroke =
            remember {
                LineCartesianLayer.LineStroke.Dashed(thickness = projectionWidth, dashLength = dashLength, gapLength = dashGap)
            },
    )

private val lineWidth = 1.5.dp
private val projectionWidth = 2.dp
private val dashLength = 8.dp
private val dashGap = 6.dp
private val dotSize = 4.dp

private const val CHARGE_LINE_ALPHA = 0.65f
private const val DISCHARGE_LINE_ALPHA = 0.65f
private const val DISCHARGE_LINE_MUTED_ALPHA = 0.35f

/** The axis parts, quieter than Vico's defaults: muted labels, outline-coloured lines. */
internal class AxisStyle(
    val label: TextComponent,
    val line: LineComponent,
    val tick: LineComponent,
    val guideline: LineComponent,
)

@Composable
internal fun rememberAxisStyle(): AxisStyle {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = Fill(MaterialTheme.colorScheme.outlineVariant)
    return AxisStyle(
        // Two lines, so a date label wraps after the date on a narrow phone instead of being cut off.
        label = rememberAxisLabelComponent(style = MaterialTheme.typography.labelSmall.copy(color = muted), lineCount = 2),
        line = rememberAxisLineComponent(fill = outline),
        tick = rememberAxisTickComponent(fill = outline),
        guideline = rememberAxisGuidelineComponent(fill = outline),
    )
}

@Composable
internal fun rememberPercentAxis(labels: List<Double>): VerticalAxis<Axis.Position.Vertical.Start> {
    val style = rememberAxisStyle()
    val formatter = remember { CartesianValueFormatter { _, value, _ -> "${formatPercent(value.toFloat())}%" } }
    return VerticalAxis.rememberStart(
        valueFormatter = formatter,
        label = style.label,
        line = style.line,
        tick = style.tick,
        guideline = style.guideline,
        itemPlacer = remember(labels) { labelsAt(labels) },
    )
}

/** Room above 100% and below 0% so a dot or line there is not clipped by the plot edge. */
internal const val Y_HEADROOM = 1.0

/** The plotted range for axis bounds [low] to [high]: padded by [Y_HEADROOM] where a bound is 0% or 100%. */
internal fun paddedRange(
    low: Double,
    high: Double,
): ClosedFloatingPointRange<Double> = (if (low <= 0.0) low - Y_HEADROOM else low)..(if (high >= PERCENT_MAX) high + Y_HEADROOM else high)

/** Gridlines and labels at exactly [values], whatever padding the plotted range carries around them. */
internal fun labelsAt(values: List<Double>): VerticalAxis.ItemPlacer {
    val base = VerticalAxis.ItemPlacer.count({ values.size })
    return object : VerticalAxis.ItemPlacer by base {
        override fun getLabelValues(
            context: CartesianDrawingContext,
            axisHeight: Float,
            maxLabelHeight: Float,
            position: Axis.Position.Vertical,
        ) = values

        override fun getWidthMeasurementLabelValues(
            context: CartesianMeasuringContext,
            axisHeight: Float,
            maxLabelHeight: Float,
            position: Axis.Position.Vertical,
        ) = values

        override fun getHeightMeasurementLabelValues(
            context: CartesianMeasuringContext,
            position: Axis.Position.Vertical,
        ) = values

        override fun getLineValues(
            context: CartesianDrawingContext,
            axisHeight: Float,
            maxLabelHeight: Float,
            position: Axis.Position.Vertical,
        ) = values
    }
}

/** Two labels, a quarter in from each end: at the very edges a date label would need a wide margin to fit. */
@Composable
internal fun rememberTimeAxis(formatter: CartesianValueFormatter): HorizontalAxis<Axis.Position.Horizontal.Bottom> {
    val style = rememberAxisStyle()
    return HorizontalAxis.rememberBottom(
        valueFormatter = formatter,
        label = style.label,
        line = style.line,
        tick = style.tick,
        guideline = style.guideline,
        itemPlacer = remember { HorizontalAxis.ItemPlacer.aligned(spacing = { 2 }, offset = { 1 }, addExtremeLabelPadding = false) },
    )
}

private const val PERCENT_MAX = 100.0

/** The plot's inset at each side: a small share of the screen width, so the lines use nearly all of it. */
@Composable
internal fun rememberEdgePadding(): (ExtraStore) -> CartesianLayerPadding {
    val edge = (LocalConfiguration.current.screenWidthDp * EDGE_SHARE).dp
    return remember(edge) { { CartesianLayerPadding(unscalableStart = edge, unscalableEnd = edge) } }
}

private const val EDGE_SHARE = 0.02f

/**
 * Drops the outer two labels, at the plot's edges, where they would need a wide
 * margin to fit; the gridlines stay.
 */
internal class InteriorLabels(
    private val placer: HorizontalAxis.ItemPlacer,
) : HorizontalAxis.ItemPlacer by placer {
    override fun getLabelValues(
        context: CartesianDrawingContext,
        visibleXRange: ClosedFloatingPointRange<Double>,
        fullXRange: ClosedFloatingPointRange<Double>,
        maxLabelWidth: Float,
    ): List<Double> = placer.getLabelValues(context, visibleXRange, fullXRange, maxLabelWidth).interior()

    override fun getWidthMeasurementLabelValues(
        context: CartesianMeasuringContext,
        layerDimensions: CartesianLayerDimensions,
        fullXRange: ClosedFloatingPointRange<Double>,
    ): List<Double> = placer.getWidthMeasurementLabelValues(context, layerDimensions, fullXRange).interior()

    override fun getLineValues(
        context: CartesianDrawingContext,
        visibleXRange: ClosedFloatingPointRange<Double>,
        fullXRange: ClosedFloatingPointRange<Double>,
        maxLabelWidth: Float,
    ): List<Double>? =
        placer.getLineValues(context, visibleXRange, fullXRange, maxLabelWidth)
            ?: placer.getLabelValues(context, visibleXRange, fullXRange, maxLabelWidth)

    // The drive chart's range starts at 0 and ends on a label step, so the outer two labels are the edges.
    private fun List<Double>.interior() = sorted().drop(1).dropLast(1)
}
