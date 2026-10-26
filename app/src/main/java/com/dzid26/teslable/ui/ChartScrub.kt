// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import kotlin.math.abs

/*
 * The chart cursor is ours, not Vico's: Vico's marker belongs to its own touch
 * handling, hides on every lift and cannot be cleared from outside, which does
 * not fit a chart inside a scrolling page. Here the chart only remembers where
 * the cursor stands (its canvas x), a decoration draws Vico's marker there, and
 * one gesture handler decides what a touch means:
 * - a touch shows the cursor under the finger at once;
 * - a drag that goes sideways first moves it and holds the page still;
 * - a drag that goes vertical first is the page scrolling: the cursor goes back
 *   to where it stood before the touch;
 * - a tap anywhere off the charts hides it; a touch on another chart leaves it standing.
 * A rotation keeps the cursor on the same point: it is saved as the data x it snapped to, since the canvas x
 * changes with the chart's width. So does leaving for Settings and coming back, which restores the car view's saved
 * state like the range chips. A saved point outside the window the chart now shows (another range or distance
 * window) is not drawn; a new sample keeps it, since the point the cursor stood on is still there.
 */

/** Where the chart's cursor stands, in the chart's canvas x; null for none. */
@Stable
internal class CursorPin(
    restoredX: Double? = null,
) {
    var canvasX by mutableStateOf<Float?>(null)

    /** The data x the cursor stood on before the screen was recreated; it stands there until a touch moves or clears it. */
    var restoredX by mutableStateOf(restoredX)

    /** The data x of the point the cursor last snapped to, which a rotation saves. Written while drawing, so not state. */
    var shownX: Double? = null

    /** The index, in the points the cursor snaps to, of the one it last snapped to; for charts whose points repeat an x. */
    var shownIndex = 0

    /** The screen-wide tap count this pin has already answered; a recreated chart must not clear on the count it finds. */
    var seenTaps = CursorDismiss.count

    fun clear() {
        canvasX = null
        restoredX = null
    }
}

internal val CursorPinSaver = Saver<CursorPin, Double>(save = { it.shownX }, restore = { CursorPin(restoredX = it) })

@Composable
internal fun rememberCursorPin(): CursorPin {
    val pin = rememberSaveable(saver = CursorPinSaver) { CursorPin() }
    val taps = CursorDismiss.count
    LaunchedEffect(taps) {
        if (taps != pin.seenTaps && CursorDismiss.chartGesture != CursorDismiss.gesture) pin.clear()
        pin.seenTaps = taps
    }
    return pin
}

/** What a move of the finger, since it landed, means for the cursor. */
internal enum class ScrubStep { UNDECIDED, SCRUB, SCROLL }

/** A sideways drag past the slop scrubs; a drag at least as vertical as it is sideways scrolls. */
internal fun scrubStep(
    delta: Offset,
    touchSlop: Float,
): ScrubStep {
    val dx = abs(delta.x)
    val dy = abs(delta.y)
    return when {
        dy > touchSlop && dy >= dx -> ScrubStep.SCROLL
        dx > touchSlop -> ScrubStep.SCRUB
        else -> ScrubStep.UNDECIDED
    }
}

/** The chart's own touch handling; see the notes at the top of this file. */
internal fun Modifier.scrubbing(pin: CursorPin): Modifier =
    pointerInput(pin) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            CursorDismiss.chartGesture = CursorDismiss.gesture
            val before = pin.canvasX
            val restoredBefore = pin.restoredX
            pin.canvasX = down.position.x
            pin.restoredX = null
            if (!followScrub(pin, down, slop)) {
                pin.canvasX = before
                pin.restoredX = restoredBefore
            }
        }
    }

/** Follows one gesture; false when it turned out to be a page scroll. */
private suspend fun AwaitPointerEventScope.followScrub(
    pin: CursorPin,
    down: PointerInputChange,
    slop: Float,
): Boolean {
    var step = ScrubStep.UNDECIDED
    var change: PointerInputChange? = down
    while (change != null && change.pressed && step != ScrubStep.SCROLL) {
        change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
        if (change == null || !change.pressed) break
        step =
            when {
                step == ScrubStep.SCRUB -> step
                // The page claimed the drag before it went sideways.
                change.isConsumed -> ScrubStep.SCROLL
                else -> scrubStep(change.position - down.position, slop)
            }
        if (step == ScrubStep.SCRUB) {
            pin.canvasX = change.position.x
            change.consume()
        }
    }
    return step != ScrubStep.SCROLL
}

/** Draws Vico's marker at the cursor, snapped to the nearest plotted point. */
internal class ScrubCursor(
    private val pin: CursorPin,
    private val marker: CartesianMarker,
    private val points: List<Pair<Double, Double>>,
    private val color: Color,
) : Decoration {
    override fun drawOverLayers(context: CartesianDrawingContext) {
        val dataX = pin.canvasX?.let { context.dataX(it) } ?: restoredCursorX(pin.restoredX, context.ranges.minX, context.ranges.maxX)
        if (dataX == null || points.isEmpty()) {
            pin.shownX = null
            return
        }
        val index = points.indices.minBy { abs(points[it].first - dataX) }
        val (x, y) = points[index]
        pin.shownX = x
        pin.shownIndex = index
        marker.drawOverLayers(context, listOf(PointTarget(x, context.canvasX(x), context.canvasY(y), y, color)))
    }
}

/** The saved cursor x when it still falls inside the plotted x range, else null. */
internal fun restoredCursorX(
    restoredX: Double?,
    minX: Double,
    maxX: Double,
): Double? = restoredX?.takeIf { it in minX..maxX }

private class PointTarget(
    override val x: Double,
    override val canvasX: Float,
    canvasY: Float,
    y: Double,
    color: Color,
) : LineCartesianLayerMarkerTarget {
    override val points = listOf(LineCartesianLayerMarkerTarget.Point(LineCartesianLayerModel.Entry(x, y), canvasY, color))
}

/**
 * Counts taps anywhere on the screen, so a standing cursor goes when the finger
 * taps outside its chart too. Drags (a page scroll) do not count.
 */
@Stable
internal object CursorDismiss {
    var count by mutableIntStateOf(0)

    /** Bumped at every touch down; a chart notes the one that landed on it. */
    var gesture = 0

    /** The last touch that landed on any chart: a tap there clears no cursor, so each chart keeps its own. */
    var chartGesture = -1
}

/** Counts every tap on this subtree without consuming anything. */
internal fun Modifier.countingTouches(): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            CursorDismiss.gesture++
            if (isTap(down.position)) CursorDismiss.count++
        }
    }

/** Follows the gesture to its lift: true when the finger never left the touch slop. */
private suspend fun AwaitPointerEventScope.isTap(start: Offset): Boolean {
    val slop = viewConfiguration.touchSlop
    var change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull()
    while (change != null && change.pressed && (change.position - start).getDistance() <= slop) {
        change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull()
    }
    return change != null && !change.pressed && (change.position - start).getDistance() <= slop
}
