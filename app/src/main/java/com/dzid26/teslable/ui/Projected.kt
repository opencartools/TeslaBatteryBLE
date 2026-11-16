// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import com.dzid26.teslable.core.history.ChargeProjection
import com.dzid26.teslable.core.history.DischargeProjection
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration

/** A series, and the wedge beside it when the projection has a beam. */
internal class Projected(
    val spec: SeriesSpec,
    val beam: Beam?,
)

/** The projection beams: translucent wedges under the lines. */
internal class Beams(
    private val beams: List<Beam>,
) : Decoration {
    override fun drawUnderLayers(context: CartesianDrawingContext) {
        with(context) {
            beams.forEach { beam ->
                val path =
                    Path().apply {
                        moveTo(canvasX(beam.xs[0]), canvasY(beam.ys[0]))
                        lineTo(canvasX(beam.xs[1]), canvasY(beam.ys[1]))
                        lineTo(canvasX(beam.xs[2]), canvasY(beam.ys[2]))
                        close()
                    }
                val fill = beam.color
                canvas.drawPath(path, Paint().apply { color = fill })
            }
        }
    }
}

internal fun chargeProjected(
    target: ChargeProjection,
    window: Window,
    color: Color,
): Projected {
    val from = TimePoint(target.from.timestampMillis, target.from.batteryLevel.toFloat())
    val spec = projectionSpec(SeriesKind.CHARGE_PROJECTION, from, chargeEnd(target, window.end, target.completionMillis), window)
    val beam =
        target.beam?.let { (earliest, latest) ->
            val points = listOf(from, chargeEnd(target, window.end, earliest), chargeEnd(target, window.end, latest))
            Beam(points.map { window.hours(it.millis) }, points.map { it.percent.toDouble() }, color.copy(alpha = CHARGE_BEAM_ALPHA))
        }
    return Projected(spec, beam)
}

internal fun dischargeProjected(
    target: DischargeProjection,
    window: Window,
    color: Color,
    muted: Boolean,
): Projected {
    val from = TimePoint(target.from.timestampMillis, target.fromSoc)
    val end = TimePoint(target.projectedAtMillis, target.projectedSoc)
    val spec = projectionSpec(SeriesKind.DISCHARGE_PROJECTION, from, end, window)
    val low = target.lowSoc
    val high = target.highSoc
    val beam =
        if (low != null && high != null) {
            Beam(
                xs = listOf(from, end, end).map { window.hours(it.millis) },
                ys = listOf(from.percent, low, high).map { it.toDouble() },
                color = color.copy(alpha = if (muted) DISCHARGE_BEAM_MUTED_ALPHA else DISCHARGE_BEAM_ALPHA),
            )
        } else {
            null
        }
    return Projected(spec, beam)
}

/**
 * The projected line as a series with points along it, so a press reads a value
 * anywhere on it and not only at its two ends.
 */
private fun projectionSpec(
    kind: SeriesKind,
    from: TimePoint,
    to: TimePoint,
    window: Window,
): SeriesSpec {
    val steps = (0..PROJECTION_POINTS).map { it.toDouble() / PROJECTION_POINTS }
    return SeriesSpec(
        kind,
        steps.map { window.hours(from.millis) + (window.hours(to.millis) - window.hours(from.millis)) * it },
        steps.map { from.percent + (to.percent - from.percent) * it },
    )
}

/**
 * Where the charge line, or an edge of its beam, ends for [completionMillis].
 * A completion past the chart's right edge is cut where its line crosses the
 * edge, so the line and the wedge keep their slope instead of flattening onto
 * the edge.
 */
internal fun chargeEnd(
    projection: ChargeProjection,
    windowEnd: Long,
    completionMillis: Long,
): TimePoint {
    if (completionMillis <= windowEnd) return TimePoint(completionMillis, projection.targetPercent.toFloat())
    val fromMillis = projection.from.timestampMillis
    val fromPercent = projection.from.batteryLevel
    val share = (windowEnd - fromMillis).toFloat() / (completionMillis - fromMillis)
    // Not rounded: far from the limit the two sides differ by well under a point.
    return TimePoint(windowEnd, fromPercent + (projection.targetPercent - fromPercent) * share)
}

private const val PROJECTION_POINTS = 8
private const val CHARGE_BEAM_ALPHA = 0.25f
private const val DISCHARGE_BEAM_ALPHA = 0.2f
private const val DISCHARGE_BEAM_MUTED_ALPHA = 0.1f
