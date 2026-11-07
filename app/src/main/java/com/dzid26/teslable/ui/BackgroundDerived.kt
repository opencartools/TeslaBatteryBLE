// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * [compute] run off the main thread whenever [keys] change, for figures derived from the sample logs (up to tens of
 * thousands of samples): on the main thread they stall the frames of every read and scroll. Until the first result
 * arrives this is [initial]; after that the previous result stays on screen while the next one is computed. When [keys]
 * change again before a computation ends, its result is dropped for the newer one.
 *
 * In inspection mode (previews and the Paparazzi snapshots) it computes inline, so a render shows the figures at once.
 */
@Composable
internal fun <T> rememberInBackground(
    initial: T,
    vararg keys: Any?,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    compute: () -> T,
): T {
    if (LocalInspectionMode.current) return remember(*keys) { compute() }
    val latest by rememberUpdatedState(compute)
    val result by produceState(initial, *keys) { value = withContext(dispatcher) { latest() } }
    return result
}

/**
 * The last result of [inBackground] and the keys it was worked out for, held by a caller that outlives the composable
 * computing it. A composable that leaves the screen and comes back (one axis of the history card while the other is
 * shown) then shows its figures at once instead of nothing, and works them out again only if its keys changed.
 */
internal class BackgroundMemo<T> {
    internal var keys: List<Any?>? = null
    internal var value: T? = null
}

/**
 * [rememberInBackground] that starts from this memo's last result rather than null, and skips the computation when
 * [keys] are the ones that result was worked out for. Null only before the first result.
 */
@Composable
internal fun <T> BackgroundMemo<T>.inBackground(
    vararg keys: Any?,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    compute: () -> T,
): T? {
    if (LocalInspectionMode.current) return remember(*keys) { compute() }
    val latest by rememberUpdatedState(compute)
    val result by produceState(value, *keys) {
        val wanted = keys.toList()
        if (this@inBackground.keys != wanted) {
            val computed = withContext(dispatcher) { latest() }
            this@inBackground.keys = wanted
            this@inBackground.value = computed
            value = computed
        }
    }
    return result
}

/**
 * [value], passed on at most once each [intervalMillis]: the first value at once, then the newest one when the
 * interval is up. A value for which [isLoaded] is false (the logs while the store is still publishing them) starts
 * no pause, so the loaded one follows at once instead of waiting out the interval. For the figures that scan a
 * car's whole history: the logs grow by a reading about every ten seconds while the car is awake, and redoing
 * those figures for each one would keep a core busy for nothing the owner can see. [value] is compared by
 * `equals`, so pass a holder with identity equality rather than a big list.
 *
 * In inspection mode it passes [value] straight through.
 */
@Composable
internal fun <T> rememberThrottled(
    value: T,
    intervalMillis: Long,
    // A new key (another car on the same screen) starts afresh: its first value passes at once.
    key: Any? = null,
    isLoaded: (T) -> Boolean = { true },
): T {
    if (LocalInspectionMode.current) return value
    val source = remember(key) { MutableStateFlow(value) }
    SideEffect { source.value = value }
    val throttled by produceState(value, source, intervalMillis) {
        // A StateFlow keeps only its newest value, so after each pause the collector gets the latest one.
        source.collect {
            this.value = it
            if (isLoaded(it)) delay(intervalMillis)
        }
    }
    return throttled
}

/** The items of [all] that belong to [vehicleId], filtered off the main thread after the first composition. */
@Composable
internal fun <T> rememberForVehicle(
    all: List<T>,
    vehicleId: String?,
    idOf: (T) -> String,
): List<T> {
    // Once, on the main thread, so the screen opens with its history; every later change filters in the background.
    val first = remember(vehicleId) { all.filter { idOf(it) == vehicleId } }
    return rememberInBackground(first, all, vehicleId) { all.filter { idOf(it) == vehicleId } }
}
