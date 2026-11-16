// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration

/*
 * The history chart's bands: stretches of time shaded behind the plot, each with an icon of its
 * own when it is wide enough on screen, and a bubble explaining it on a tap. The bands are drawn
 * under the layers, so the grid, the projection beams and the measured line all stay on top; the
 * icons go over everything, since an icon is a control rather than data.
 */

/** The two ends of a band's gradient: its tint at the top of the plot and at the bottom. */
private class BandColors(
    val top: Color,
    val bottom: Color,
)

/**
 * The bands in the tint their surface can carry: a neutral that reads on both themes, but needs a
 * firmer alpha on a dark one. The scheme's own surface says which it is, so a wallpaper-derived
 * palette picks correctly too.
 */
@Composable
internal fun rememberHistoryBands(
    bands: List<Band>,
    icons: BandIcons,
): Decoration {
    val dark = MaterialTheme.colorScheme.background.luminance() < DARK_BACKGROUND_LUMINANCE
    val tint = MaterialTheme.colorScheme.onSurface
    val colors = remember(dark, tint) { bandColors(dark, tint) }
    val density = LocalDensity.current
    val style =
        remember(density) {
            with(density) {
                IconStyle(sizePx = ICON_SIZE.toPx(), insetPx = ICON_INSET.toPx(), minBandPx = ICON_MIN_BAND.toPx())
            }
        }
    // A range change or new samples move the bands: the open bubble would point at an empty spot.
    LaunchedEffect(bands) { icons.open = null }
    return remember(bands, colors, icons, style) { HistoryBands(bands, colors, icons, style) }
}

private fun bandColors(
    dark: Boolean,
    tint: Color,
): BandColors {
    val top = if (dark) BAND_DARK_TOP_ALPHA else BAND_LIGHT_TOP_ALPHA
    val bottom = if (dark) BAND_DARK_BOTTOM_ALPHA else BAND_LIGHT_BOTTOM_ALPHA
    return BandColors(tint.copy(alpha = top), tint.copy(alpha = bottom))
}

/**
 * A gradient in each band, from the top of the plot down, as the drive chart's gain bands run.
 * Drawn under the layers, so nothing the chart reads from is hidden.
 */
private class HistoryBands(
    private val bands: List<Band>,
    private val colors: BandColors,
    private val icons: BandIcons,
    private val style: IconStyle,
) : Decoration {
    private val iconFill = Paint().apply { color = ICON_FILL }

    // Hoisted: inside Paint().apply, `style` would resolve to Paint.style.
    private val glyphSize = style.sizePx * ICON_GLYPH_SHARE
    private val glyphPaint =
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = glyphSize
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
        }

    override fun drawUnderLayers(context: CartesianDrawingContext) {
        with(context) {
            if (bands.isEmpty()) return
            val paint =
                Paint().apply {
                    shader =
                        LinearGradientShader(
                            from = Offset(0f, layerBounds.top),
                            to = Offset(0f, layerBounds.bottom),
                            colors = listOf(colors.top, colors.bottom),
                        )
                }
            bands.forEach { band ->
                // Left to right on screen, whichever way the layout runs.
                val start = canvasX(band.startHours)
                val end = canvasX(band.endHours)
                canvas.drawRect(minOf(start, end), layerBounds.top, maxOf(start, end), layerBounds.bottom, paint)
            }
        }
    }

    override fun drawOverLayers(context: CartesianDrawingContext) {
        val visible = context.ranges.minX..context.ranges.maxX
        val drawn = bandIcons(bands, visible, { context.canvasX(it) }, context.layerBounds.top + style.insetPx, style)
        drawn.forEach { context.drawIcon(it, iconFill, glyphPaint) }
        icons.icons = drawn
    }
}

/** A blue square with a white P: the European parking sign, the icon of a power-down stretch. */
private fun CartesianDrawingContext.drawIcon(
    icon: BandIcon,
    fill: Paint,
    glyph: android.graphics.Paint,
) {
    val radius = (icon.right - icon.left) * ICON_CORNER_SHARE
    canvas.drawRoundRect(icon.left, icon.top, icon.right, icon.bottom, radius, radius, fill)
    val baseline = (icon.top + icon.bottom) / 2 - (glyph.descent() + glyph.ascent()) / 2
    canvas.nativeCanvas.drawText(icon.kind.glyph, (icon.left + icon.right) / 2, baseline, glyph)
}

/** Material Blue 800 at half strength for the sign itself: a parking sign is blue on any theme, and it reads on gray. */
private val ICON_FILL = Color(0xFF1565C0).copy(alpha = 0.5f)

private val ICON_SIZE = 16.dp
private val ICON_INSET = 6.dp
private val ICON_MIN_BAND = 32.dp

private const val ICON_GLYPH_SHARE = 0.72f
private const val ICON_CORNER_SHARE = 0.18f

private const val BAND_LIGHT_TOP_ALPHA = 0.10f
private const val BAND_LIGHT_BOTTOM_ALPHA = 0.02f
private const val BAND_DARK_TOP_ALPHA = 0.16f
private const val BAND_DARK_BOTTOM_ALPHA = 0.03f

/** A background this dark is a dark scheme: the light ones sit near white, the dark ones near black. */
private const val DARK_BACKGROUND_LUMINANCE = 0.5f
