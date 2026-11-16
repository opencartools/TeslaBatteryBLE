// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.ui.graphics.Color
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker

/** The pieces the battery chart's decorations are built from. */
internal class ChartLayers(
    val shape: BatteryShape,
    val pin: CursorPin,
    val cursor: DefaultCartesianMarker,
    val lineColor: Color,
    val viewport: ChartViewport,
    val bands: Decoration,
)

/** The chart's decorations: the tracked plot area, the bands, the beams and the cursor. */
internal fun batteryDecorations(layers: ChartLayers): List<Decoration> =
    with(layers) {
        listOf(
            ViewportTracker(viewport),
            // The power-down bands sit lowest: the grid, the beams and the readings stay on top.
            ClippedWhenZoomed(bands, viewport),
            ClippedWhenZoomed(Beams(shape.beams), viewport),
            ScrubCursor(pin, cursor, shape.specs.flatMap { it.xs.zip(it.ys) }, lineColor),
        )
    }
