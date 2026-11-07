// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent

/** The cursor's text for the pressed point: its data x and y. */
internal fun interface PointDescriber {
    fun describe(
        x: Double,
        y: Double,
    ): String
}

/** The hint while a span is selected: from the held point to the finger's, each as data x and y. */
internal fun interface SpanDescriber {
    fun describe(
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
    ): String
}

/** Feeds the first point under the cursor to a [PointDescriber], or the span to [span] while one is selected. */
private class PointFormatter(
    private val describer: PointDescriber,
    private val span: SpanDescriber?,
    private val pin: CursorPin?,
) : DefaultCartesianMarker.ValueFormatter {
    override fun format(
        context: CartesianDrawingContext,
        targets: List<CartesianMarker.Target>,
    ): CharSequence {
        val point =
            targets
                .filterIsInstance<LineCartesianLayerMarkerTarget>()
                .firstOrNull()
                ?.points
                ?.firstOrNull()
        val anchor = pin?.anchorPoint?.takeIf { pin.anchorX != null }
        return when {
            point == null -> ""
            anchor != null && span != null -> span.describe(anchor, point.entry.x to point.entry.y)
            else -> describer.describe(point.entry.x, point.entry.y)
        }
    }
}

/**
 * The cursor both charts share: a vertical line at the pressed point and a
 * readable label on a surface bubble, in the theme's colours (Vico's default
 * draws neither the line nor a themed label).
 */
@Composable
internal fun rememberChartCursor(
    describer: PointDescriber,
    // The hint while a held press selects a span on [pin]; null keeps the point hint throughout.
    span: SpanDescriber? = null,
    pin: CursorPin? = null,
): DefaultCartesianMarker {
    val line = Fill(MaterialTheme.colorScheme.onSurfaceVariant)
    val bubble =
        rememberShapeComponent(fill = Fill(MaterialTheme.colorScheme.surfaceContainerHigh), shape = RoundedCornerShape(cornerRadius))
    return rememberDefaultCartesianMarker(
        label =
            rememberTextComponent(
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                background = bubble,
                padding = Insets(paddingX, paddingY),
            ),
        valueFormatter = remember(describer, span, pin) { PointFormatter(describer, span, pin) },
        guideline = rememberAxisGuidelineComponent(fill = line),
    )
}

private val cornerRadius = 4.dp
private val paddingX = 6.dp
private val paddingY = 3.dp
