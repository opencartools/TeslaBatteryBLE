// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.android.resources.Density
import com.dzid26.teslable.core.history.FullRangeScale
import com.dzid26.teslable.core.history.RouteSample
import com.dzid26.teslable.core.history.Trip
import com.dzid26.teslable.core.history.segmentTrips

/**
 * The narrow-width render: Jelly Star width (3-inch, 480px
 * @320dpi -> 240dp), hand-built because no preset goes that narrow, and tall
 * enough that a card is never cut off before its last line.
 * Light only; the PR comment keeps it collapsed next to dark.
 */
internal val jellyStar =
    DeviceConfig.NEXUS_5.copy(
        screenWidth = 480,
        screenHeight = 2400,
        xdpi = 327,
        ydpi = 327,
        density = Density.XHIGH,
    )

/**
 * One state, three images: the card in the fixed light scheme and in the
 * fixed dark scheme, plus a narrow Jelly Star render in light, all
 * SHRINK-wrapped so each bitmap hugs the card (full-width x
 * content-height, no black void). The theme is always the last name
 * segment, so the workflow can group by state.
 */
internal fun Paparazzi.snapCard(
    name: String,
    content: @Composable () -> Unit,
) {
    unsafeUpdateConfig(
        // Narrowest phone Paparazzi ships (360dp): the width under test.
        deviceConfig = DeviceConfig.NEXUS_5,
        renderingMode = SessionParams.RenderingMode.SHRINK,
    )
    snapshot(name + "_light") {
        CardFrame(darkTheme = false, content = content)
    }
    snapshot(name + "_dark") {
        CardFrame(darkTheme = true, content = content)
    }
    // The Jelly Star is narrower than anything Paparazzi ships, so hand-built via copy();
    // light only, collapsed in the PR comment next to dark.
    unsafeUpdateConfig(
        deviceConfig = jellyStar,
        renderingMode = SessionParams.RenderingMode.SHRINK,
    )
    snapshot(name + "_jelly_light") {
        CardFrame(darkTheme = false, content = content)
    }
}

/** A pinned 270-mile full range, so the trip cards can show the rated range an arrival level stands for. */
internal val demoTripScale =
    FullRangeScale(milesPerPercent = 2.7f, offsetMiles = 0f, standardErrorMiles = 0.1f, samples = 40, pinned = true)

/** How long before the render the demo past trips began. */
internal const val DEMO_TRIP_AGO_MILLIS = 26 * 3_600_000L

private const val DEMO_MINUTE_MILLIS = 60_000L

/** Two finished navigations, the newer one 18 minutes long. */
internal fun demoPastTrips(now: Long): List<Trip> {
    val home = now - DEMO_TRIP_AGO_MILLIS
    val work = now - 2 * DEMO_TRIP_AGO_MILLIS
    return segmentTrips(
        listOf(
            demoRoute(home, "Home", 11.4f, 26f, 57f),
            demoRoute(home + 6 * DEMO_MINUTE_MILLIS, "Home", 7.2f, 17f, 57f),
            demoRoute(home + 12 * DEMO_MINUTE_MILLIS, "Home", 1.2f, 3f, 56f),
            demoRoute(work, "Work", 18.3f, 40f, 38f),
            demoRoute(work + 8 * DEMO_MINUTE_MILLIS, "Work", 12.0f, 28f, 38f),
        ),
    )
}

private fun demoRoute(
    at: Long,
    destination: String,
    miles: Float,
    minutes: Float,
    arrivalPercent: Float,
) = RouteSample(at, "demo", destination, miles, minutes, 0f, arrivalPercent)
