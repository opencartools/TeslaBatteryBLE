// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The chart, with its band icon's explanation floating over it when one is open. */
@Composable
internal fun BandIconHost(
    modifier: Modifier,
    icons: BandIcons,
    content: @Composable BoxScope.() -> Unit,
) {
    var width by remember { mutableIntStateOf(0) }
    Box(modifier.onSizeChanged { width = it.width }) {
        content()
        BandIconBubble(icons, width)
    }
}

/**
 * The icon's explanation, in a small bubble under it. A tap anywhere on the chart closes it, and so
 * does Back: the overlay takes the tap, so the chart underneath neither scrubs nor moves its cursor
 * while the bubble is open.
 */
@Composable
internal fun BoxScope.BandIconBubble(
    icons: BandIcons,
    chartWidthPx: Int,
) {
    val icon = icons.open ?: return
    BackHandler { icons.open = null }
    val density = LocalDensity.current
    val maxLeftPx = with(density) { (chartWidthPx - BUBBLE_MAX_WIDTH.toPx()).coerceAtLeast(0f) }
    val at =
        with(density) {
            IntOffset(
                x = icon.left.coerceIn(0f, maxLeftPx).roundToInt(),
                y = (icon.bottom + BUBBLE_GAP.toPx()).roundToInt(),
            )
        }
    Box(Modifier.matchParentSize().pointerInput(icons) { detectTapGestures { icons.open = null } })
    Surface(
        modifier = Modifier.offset { at }.widthIn(max = BUBBLE_MAX_WIDTH),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 3.dp,
    ) {
        Text(
            text = stringResource(icon.kind.hint),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private val BUBBLE_GAP = 6.dp
private val BUBBLE_MAX_WIDTH = 260.dp
