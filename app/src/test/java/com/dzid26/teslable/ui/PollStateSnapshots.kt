// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import app.cash.paparazzi.Paparazzi
import com.dzid26.teslable.ui.CardSnapshots.Companion.FRESH_MINUTES
import com.dzid26.teslable.ui.CardSnapshots.Companion.MINUTE
import com.dzid26.teslable.ui.CardSnapshots.Companion.NOW
import com.dzid26.teslable.ui.CardSnapshots.Companion.advert
import com.dzid26.teslable.ui.CardSnapshots.Companion.liveConnection
import com.dzid26.teslable.ui.CardSnapshots.Companion.vehicle
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Paparazzi snapshot of the car card while the poll waits out its idle interval, with the fixtures
 * of [CardSnapshots]: the Paused pill. Its explanation is a toast on tap, which no snapshot renders.
 */
class PollStateSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    private val defaultLocale: Locale = Locale.getDefault()

    // Dates read the default locale; pin it so renders match across machines.
    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun heroCardPaused() {
        paparazzi.snapCard("hero-card_paused") {
            HeroCard(
                connection = liveConnection(readAt = NOW - FRESH_MINUTES * MINUTE).copy(pausedUntilMillis = NOW + PAUSED_MINUTES * MINUTE),
                advert = advert,
                vehicle = vehicle,
                history = emptyList(),
                statusHistory = emptyList(),
                nowMillis = NOW,
            )
        }
    }

    private companion object {
        /** Far enough ahead that the pill's next read is not the current minute. */
        const val PAUSED_MINUTES = 12L
    }
}
