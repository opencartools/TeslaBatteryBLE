// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.runtime.CompositionLocalProvider
import app.cash.paparazzi.Paparazzi
import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.health.PriceChange
import com.dzid26.teslable.core.health.SessionPrice
import com.dzid26.teslable.core.health.StoredChargePrice
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Paparazzi snapshots of the charge sessions card, in the card flow of [CardSnapshots]. */
class ChargeSessionSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    private val defaultLocale: Locale = Locale.getDefault()

    // Dates read the default locale; pin it so renders match across machines.
    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun chargeSessionsCard() = snap("charge-sessions-card", listOf(earlier, finished))

    @Test
    fun chargeSessionsCardInProgress() = snap("charge-sessions-card_in-progress", listOf(earlier, finished.copy(inProgress = true)))

    @Test
    fun chargeSessionsCardNoEnergy() =
        snap("charge-sessions-card_no-energy", listOf(finished.copy(energyAddedKwh = null, milesAddedRated = null)))

    @Test
    fun chargeSessionsCardNoPrice() = snap("charge-sessions-card_no-price", listOf(earlier, finished), price = null)

    // Editable, with the owner's total in euros for the open session: the Edit price button shows, and the total
    // keeps its own currency and the energy it paid for.
    @Test
    fun chargeSessionsCardEditedTotal() =
        snap(
            "charge-sessions-card_edited-total",
            listOf(earlier, finished),
            pricing =
                ChargePricing(
                    paid = emptyMap(),
                    stored =
                        listOf(
                            StoredChargePrice(
                                "",
                                finished.startMillis,
                                finished.endMillis,
                                SessionPrice.Total(18.4f),
                                "EUR",
                                byOwner = true,
                                null,
                            ),
                        ),
                ) { _, _, _, _ -> },
        )

    // A home price set before both sessions, so each one shows its cost.
    private fun snap(
        name: String,
        sessions: List<ChargeSession>,
        price: Float? = 0.15f,
        pricing: ChargePricing? = null,
    ) = paparazzi.snapCard(name) {
        CompositionLocalProvider(
            LocalPriceChanges provides listOfNotNull(price?.let { PriceChange(0L, it) }),
            LocalCurrencyCode provides "USD",
            LocalChargePricing provides pricing,
        ) {
            ChargeSessionsCard(sessions)
        }
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
        const val HOUR = 3_600_000L

        val earlier =
            ChargeSession(NOW - 30 * HOUR, NOW - 28 * HOUR, 22f, 61f, energyAddedKwh = 29.4f, milesAddedRated = 131f)
        val finished =
            ChargeSession(NOW - 9 * HOUR, NOW - 5 * HOUR, 18f, 80f, energyAddedKwh = 46.1f, milesAddedRated = 207f)
    }
}
