// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The history chart's bands: stretches of time shaded behind the plot, each with an icon of its
 * own when it is wide enough on screen, and a bubble explaining it on a tap. The bands are drawn
 * under the layers, so the grid, the projection beams and the measured line all stay on top; the
 * icons go over everything, since an icon is a control rather than data.
 */

/** A kind's band gradient: the tint, and the two ends of the fade down the plot. */
private class BandColors(
    val top: Color,
    val bottom: Color,
)

/**
 * The bands in the tint their surface can carry: parked takes the scheme's neutral, sleep indigo and
 * climate amber, each with a firmer alpha on a dark surface. The scheme's own surface says which
 * theme it is, so a wallpaper-derived palette picks correctly too. [unseen] stretches get diagonal
 * stripes in a deeper shade of the sleep tint, a texture of the band rather than a foreign gray.
 */
@Composable
internal fun rememberHistoryBands(
    bands: List<Band>,
    unseen: List<ClosedFloatingPointRange<Double>>,
    icons: BandIcons,
): Decoration {
    val dark = MaterialTheme.colorScheme.background.luminance() < DARK_BACKGROUND_LUMINANCE
    val surface = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val bandStyle =
        remember(dark, surface, density) {
            makeBandStyle(
                dark = dark,
                surface = surface,
                hatchSpacingPx = with(density) { HATCH_SPACING.toPx() },
                hatchStrokePx = with(density) { HATCH_STROKE.toPx() },
            )
        }
    val iconStyle =
        remember(density) {
            with(density) {
                IconStyle(
                    sizePx = ICON_SIZE.toPx(),
                    insetPx = ICON_INSET.toPx(),
                    airPx = ICON_AIR.toPx(),
                    minSizePx = ICON_MIN_SIZE.toPx(),
                )
            }
        }
    // A range change or new samples move the bands: the open bubble would point at an empty spot.
    LaunchedEffect(bands, unseen) { icons.open = null }
    return remember(bands, unseen, bandStyle, icons, iconStyle) {
        HistoryBands(bands, unseen, bandStyle, icons, iconStyle)
    }
}

/** How a band and its unseen stretches look: the gradient colours and the stripes that mark lost stretches. */
private class BandStyle(
    val colors: Map<BandKind, BandColors>,
    val hatchColor: Color,
    val hatchSpacingPx: Float,
    val hatchStrokePx: Float,
)

private fun makeBandStyle(
    dark: Boolean,
    surface: Color,
    hatchSpacingPx: Float,
    hatchStrokePx: Float,
): BandStyle {
    val top = if (dark) BAND_DARK_TOP_ALPHA else BAND_LIGHT_TOP_ALPHA
    val bottom = if (dark) BAND_DARK_BOTTOM_ALPHA else BAND_LIGHT_BOTTOM_ALPHA
    val colors =
        BandKind.entries.associateWith { kind ->
            val tint =
                when (kind) {
                    BandKind.PARKED -> surface
                    else -> kind.tile
                }
            BandColors(tint.copy(alpha = top), tint.copy(alpha = bottom))
        }
    return BandStyle(
        colors = colors,
        // A deeper alpha of the sleep tint: the stripes read as the band's own texture, not a gray laid on top.
        hatchColor = ASLEEP_TINT.copy(alpha = if (dark) HATCH_DARK_ALPHA else HATCH_LIGHT_ALPHA),
        hatchSpacingPx = hatchSpacingPx,
        hatchStrokePx = hatchStrokePx,
    )
}

/**
 * A gradient in each band, from the top of the plot down, as the drive chart's gain bands run.
 * Drawn under the layers, so nothing the chart reads from is hidden.
 */
private class HistoryBands(
    private val bands: List<Band>,
    private val unseen: List<ClosedFloatingPointRange<Double>>,
    private val bandStyle: BandStyle,
    private val icons: BandIcons,
    private val iconStyle: IconStyle,
) : Decoration {
    private val white = Paint().apply { color = Color.White }

    private val hatch =
        Paint().apply {
            color = bandStyle.hatchColor
            style = PaintingStyle.Stroke
            strokeWidth = bandStyle.hatchStrokePx
        }

    private val glyphPaint =
        android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
        }

    private val iconFills = BandKind.entries.associateWith { kind -> Paint().apply { color = kind.tile.copy(alpha = ICON_FILL_ALPHA) } }

    override fun drawUnderLayers(context: CartesianDrawingContext) {
        with(context) {
            if (bands.isEmpty()) return
            val paints =
                bandStyle.colors.mapValues { (_, band) ->
                    Paint().apply {
                        shader =
                            LinearGradientShader(
                                from = Offset(0f, layerBounds.top),
                                to = Offset(0f, layerBounds.bottom),
                                colors = listOf(band.top, band.bottom),
                            )
                    }
                }
            bands.forEach { band ->
                // Left to right on screen, whichever way the layout runs.
                val start = canvasX(band.startHours)
                val end = canvasX(band.endHours)
                canvas.drawRect(minOf(start, end), layerBounds.top, maxOf(start, end), layerBounds.bottom, paints.getValue(band.kind))
            }
            drawHatch(unseen)
        }
    }

    /** Diagonal stripes over every stretch the log lost: one pattern across the plot, so its phase does not restart per band. */
    private fun CartesianDrawingContext.drawHatch(spans: List<ClosedFloatingPointRange<Double>>) {
        val top = layerBounds.top
        val bottom = layerBounds.bottom
        val height = bottom - top
        if (spans.isEmpty() || height <= 0f) return
        val lost = Path()
        spans.forEach { span ->
            val start = canvasX(span.start)
            val end = canvasX(span.endInclusive)
            val left = minOf(start, end)
            val right = maxOf(start, end)
            if (right > left) lost.addRect(Rect(left, top, right, bottom))
        }
        if (lost.isEmpty) return
        canvas.save()
        canvas.clipPath(lost)
        // Anchored to the plot, not to each stretch: the stripes line up across every band.
        var x = layerBounds.left - height
        while (x < layerBounds.right) {
            canvas.drawLine(Offset(x, bottom), Offset(x + height, top), hatch)
            x += bandStyle.hatchSpacingPx
        }
        canvas.restore()
    }

    override fun drawOverLayers(context: CartesianDrawingContext) {
        val visible = context.ranges.minX..context.ranges.maxX
        val drawn =
            bandIcons(
                bands,
                visible,
                { context.canvasX(it) },
                IconEdges(context.layerBounds.top + iconStyle.insetPx, context.layerBounds.bottom - iconStyle.insetPx),
                iconStyle,
            )
        drawn.forEach { icon -> context.drawIcon(icon, iconFills.getValue(icon.kind)) }
        icons.icons = drawn
    }

    private fun CartesianDrawingContext.drawIcon(
        icon: BandIcon,
        fill: Paint,
    ) {
        val size = icon.right - icon.left
        val radius = size * ICON_CORNER_SHARE
        canvas.drawRoundRect(icon.left, icon.top, icon.right, icon.bottom, radius, radius, fill)
        when (icon.kind) {
            BandKind.PARKED -> drawLetter(icon, size)
            BandKind.ASLEEP -> drawCrescent(icon, size)
            BandKind.CLIMATE -> drawSnowflake(icon, size)
        }
    }

    /** A blue square with a white P: the European parking sign, the icon of a power-down stretch. */
    private fun CartesianDrawingContext.drawLetter(
        icon: BandIcon,
        size: Float,
    ) {
        glyphPaint.textSize = size * ICON_GLYPH_SHARE
        val baseline = (icon.top + icon.bottom) / 2 - (glyphPaint.descent() + glyphPaint.ascent()) / 2
        canvas.nativeCanvas.drawText("P", (icon.left + icon.right) / 2, baseline, glyphPaint)
    }

    /** A crescent: a white disc with a second disc bitten out of its upper right. */
    private fun CartesianDrawingContext.drawCrescent(
        icon: BandIcon,
        size: Float,
    ) {
        val radius = size * MOON_RADIUS_SHARE
        val centre = Offset((icon.left + icon.right) / 2, (icon.top + icon.bottom) / 2)
        val outer = Path().apply { addOval(Rect(center = centre, radius = radius)) }
        val bite = Path().apply { addOval(Rect(center = centre + Offset(radius * 0.55f, -radius * 0.35f), radius = radius * 0.85f)) }
        val moon = Path().apply { op(outer, bite, PathOperation.Difference) }
        canvas.drawPath(moon, white)
    }

    /** A snowflake: six thin arms, each forking near its tip into a pair of short branches that grow towards the end. */
    private fun CartesianDrawingContext.drawSnowflake(
        icon: BandIcon,
        size: Float,
    ) {
        val radius = size * FLAKE_RADIUS_SHARE
        val centre = Offset((icon.left + icon.right) / 2, (icon.top + icon.bottom) / 2)
        val stroke =
            Paint().apply {
                color = Color.White
                style = PaintingStyle.Stroke
                strokeWidth = size * FLAKE_STROKE_SHARE
                strokeCap = StrokeCap.Round
            }
        val forked = size >= iconStyle.sizePx * FLAKE_FORK_MIN_SHARE
        repeat(FLAKE_ARMS) { arm ->
            val angle = 2 * PI * arm / FLAKE_ARMS
            val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
            canvas.drawLine(centre, centre + direction * radius, stroke)
            if (!forked) return@repeat
            val joint = centre + direction * (radius * FLAKE_FORK_AT)
            for (spread in listOf(FLAKE_FORK_ANGLE, -FLAKE_FORK_ANGLE)) {
                val branch = Offset(cos(angle + spread).toFloat(), sin(angle + spread).toFloat())
                canvas.drawLine(joint, joint + branch * (radius * FLAKE_FORK_LENGTH), stroke)
            }
        }
    }
}

private val ICON_SIZE = 16.dp
private val ICON_INSET = 6.dp
private val ICON_AIR = 1.dp
private val ICON_MIN_SIZE = 8.dp

/** The tile's strength: the kind's tint at half strength, so the white mark reads on it. */
private const val ICON_FILL_ALPHA = 0.5f

private const val ICON_GLYPH_SHARE = 0.72f
private const val ICON_CORNER_SHARE = 0.18f
private const val MOON_RADIUS_SHARE = 0.30f
private const val FLAKE_RADIUS_SHARE = 0.37f
private const val FLAKE_STROKE_SHARE = 0.055f
private const val FLAKE_ARMS = 6
private const val FLAKE_FORK_AT = 0.62f
private const val FLAKE_FORK_LENGTH = 0.26f
private const val FLAKE_FORK_MIN_SHARE = 0.62f

/** A branch leaves its arm at a sixth of a turn: the classic snowflake fork. */
private const val FLAKE_FORK_ANGLE = PI / 3

/** Stripes over the parts of a band the log lost: a deeper sleep tint, thick enough to read on a narrow band. */
private val HATCH_SPACING = 6.dp
private val HATCH_STROKE = 2.dp
private const val HATCH_LIGHT_ALPHA = 0.24f
private const val HATCH_DARK_ALPHA = 0.34f

private const val BAND_LIGHT_TOP_ALPHA = 0.10f
private const val BAND_LIGHT_BOTTOM_ALPHA = 0.02f
private const val BAND_DARK_TOP_ALPHA = 0.16f
private const val BAND_DARK_BOTTOM_ALPHA = 0.03f

/** A background this dark is a dark scheme: the light ones sit near white, the dark ones near black. */
private const val DARK_BACKGROUND_LUMINANCE = 0.5f
