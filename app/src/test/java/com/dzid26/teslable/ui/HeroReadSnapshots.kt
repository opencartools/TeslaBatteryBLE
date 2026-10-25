// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import app.cash.paparazzi.Paparazzi
import com.dzid26.teslable.ble.ReadProgress
import com.dzid26.teslable.ui.CardSnapshots.Companion.FRESH_MINUTES
import com.dzid26.teslable.ui.CardSnapshots.Companion.MINUTE
import com.dzid26.teslable.ui.CardSnapshots.Companion.NOW
import com.dzid26.teslable.ui.CardSnapshots.Companion.advert
import com.dzid26.teslable.ui.CardSnapshots.Companion.learntCharge
import com.dzid26.teslable.ui.CardSnapshots.Companion.learntSamples
import com.dzid26.teslable.ui.CardSnapshots.Companion.liveConnection
import com.dzid26.teslable.ui.CardSnapshots.Companion.vehicle
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Paparazzi snapshots of the read ring beside the hero card's big reading, with the fixtures of [CardSnapshots]: next to
 * a percent with its decimal (the widest reading) and next to the connection state before any reading.
 */
class HeroReadSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    private val defaultLocale: Locale = Locale.getDefault()

    // Dates read the default locale; pin it so renders match across machines.
    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun heroCardSpinnerBesideDecimalPercent() {
        paparazzi.snapCard("hero-card_spinner-decimal") {
            HeroCard(
                connection =
                    liveConnection(
                        readAt = NOW - FRESH_MINUTES * MINUTE,
                        charge = learntCharge,
                        readProgress = ReadProgress(done = 2, total = 5),
                    ),
                advert = advert,
                vehicle = vehicle,
                history = learntSamples,
                statusHistory = emptyList(),
                nowMillis = NOW,
                spinnerOverride = true,
            )
        }
    }

    @Test
    fun heroCardSpinnerBesideConnected() {
        paparazzi.snapCard("hero-card_spinner-connected") {
            HeroCard(
                connection = liveConnection(charge = null, readProgress = ReadProgress(done = 0, total = 5)),
                advert = advert,
                vehicle = vehicle,
                history = emptyList(),
                statusHistory = emptyList(),
                nowMillis = NOW,
                spinnerOverride = true,
            )
        }
    }
}
