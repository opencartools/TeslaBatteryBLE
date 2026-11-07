// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.runtime.CompositionLocalProvider
import app.cash.paparazzi.Paparazzi
import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.health.ChargeSessionPrice
import com.dzid26.teslable.core.health.ChargerKind
import com.dzid26.teslable.core.health.PriceChange
import com.dzid26.teslable.core.health.SessionPrice
import com.dzid26.teslable.core.health.StoredChargePrice
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.DriveSession
import com.dzid26.teslable.core.history.Session
import com.dzid26.teslable.protocol.LastPaidCharge
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Paparazzi snapshots of the Sessions card, in the card flow of [CardSnapshots]: drives (two navigated, one not) and
 * charges by day, closed and with each kind of row open.
 */
class SessionsCardSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    private val defaultLocale: Locale = Locale.getDefault()

    // Dates read the default locale; pin it so renders match across machines.
    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun sessionsCard() = snap("sessions-card")

    @Test
    fun sessionsCardChargeOpen() = snap("sessions-card_charge-open", selected = finished)

    @Test
    fun sessionsCardTripOpen() = snap("sessions-card_trip-open", selected = homeDrive)

    @Test
    fun sessionsCardDriveOpen() = snap("sessions-card_drive-open", selected = plainDrive)

    @Test
    fun sessionsCardNoPrice() = snap("sessions-card_no-price", selected = finished, price = null)

    @Test
    fun sessionsCardCharging() = snap("sessions-card_charging", sessions = sessions.dropLast(1) + finished.copy(inProgress = true))

    @Test
    fun sessionsCardNoEnergy() =
        snap("sessions-card_no-energy", sessions = listOf(finished.copy(energyAddedKwh = null, milesAddedRated = null)))

    // Editable, with the owner's total in euros for the open charge: the Edit price button shows under the cost, and
    // the total keeps its own currency and the energy it paid for.
    @Test
    fun sessionsCardEditedTotal() =
        snap(
            "sessions-card_edited-total",
            selected = finished,
            pricing =
                ChargePricing(
                    paid = emptyMap(),
                    stored =
                        listOf(
                            StoredChargePrice(
                                "demo",
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

    // A Supercharger charge the car reported its cost for: the row and the figures show the car's total in its own
    // currency.
    @Test
    fun sessionsCardSupercharger() =
        snap(
            "sessions-card_supercharger",
            sessions = sessions + fast,
            selected = fast,
            pricing =
                ChargePricing(
                    stored = emptyList(),
                    paid = mapOf("demo" to listOf(LastPaidCharge(fast.endMillis, 12.9f, "EUR", 0.48f))),
                ) { _, _, _, _ -> },
        )

    // The edit dialog's own surface (Paparazzi renders no dialog window): a home-priced charge, per kWh, with the
    // "Home" price offered.
    @Test
    fun editPriceDialog() =
        paparazzi.snapCard("edit-price-dialog") {
            CompositionLocalProvider(LocalCurrencyCode provides "USD") {
                EditPricePanel(
                    PricedSession(finished, ChargeSessionPrice(SessionPrice.PerKwh(0.15f), null, byOwner = false, place = null)),
                    currencyCode = "USD",
                    suggestion = PriceSuggestion(supercharger = false, pricePerKwh = 0.3f, currencyCode = "USD"),
                    onSave = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

    // A home price set before every session, so each charge shows its cost.
    private fun snap(
        name: String,
        sessions: List<Session> = SessionsCardSnapshots.sessions,
        selected: Session? = null,
        price: Float? = 0.15f,
        pricing: ChargePricing? = null,
    ) = paparazzi.snapCard(name) {
        CompositionLocalProvider(
            LocalPriceChanges provides listOfNotNull(price?.let { PriceChange(0L, it) }),
            LocalCurrencyCode provides "USD",
            LocalIs24Hour provides false,
            LocalChargePricing provides pricing,
        ) {
            SessionsCard(
                sessions = sessions,
                trips = demoPastTrips(NOW),
                inputs = SessionInputs(samples, demoTripScale, ratedKwhPerMile = 0.25f, pricePerKwh = price),
                selectedId = selected?.id,
                onSelect = {},
            )
        }
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
        const val HOUR = 3_600_000L
        const val MINUTE = 60_000L

        val workDrive =
            DriveSession(
                NOW - 2 * DEMO_TRIP_AGO_MILLIS,
                NOW - 2 * DEMO_TRIP_AGO_MILLIS + 22 * MINUTE,
                "demo",
                startOdometer = 1_000_00,
                endOdometer = 1_018_30,
                socStartPercent = 64f,
                socEndPercent = 58f,
            )
        val earlier =
            ChargeSession(NOW - 30 * HOUR, NOW - 28 * HOUR, 22f, 61f, energyAddedKwh = 29.4f, milesAddedRated = 131f, vehicleId = "demo")
        val homeDrive =
            DriveSession(
                NOW - DEMO_TRIP_AGO_MILLIS,
                NOW - DEMO_TRIP_AGO_MILLIS + 14 * MINUTE,
                "demo",
                startOdometer = 1_018_30,
                endOdometer = 1_029_70,
                socStartPercent = 60f,
                socEndPercent = 56f,
            )
        val plainDrive =
            DriveSession(
                NOW - 11 * HOUR,
                NOW - 11 * HOUR + 26 * MINUTE,
                "demo",
                startOdometer = 1_029_70,
                endOdometer = 1_044_10,
                socStartPercent = 56f,
                socEndPercent = 50f,
            )
        val finished =
            ChargeSession(NOW - 9 * HOUR, NOW - 5 * HOUR, 50f, 80f, energyAddedKwh = 23.1f, milesAddedRated = 101f, vehicleId = "demo")

        val fast =
            ChargeSession(
                NOW - 3 * HOUR,
                NOW - 3 * HOUR + 25 * MINUTE,
                12f,
                64f,
                energyAddedKwh = 38.7f,
                milesAddedRated = 174f,
                chargerKind = ChargerKind.DC,
                vehicleId = "demo",
            )

        val sessions: List<Session> = listOf(workDrive, earlier, homeDrive, plainDrive, finished)

        val samples =
            listOf(
                BatterySample(NOW - DEMO_TRIP_AGO_MILLIS - 5 * MINUTE, 60, null, null, ratedRangeMiles = 162f),
                BatterySample(NOW - DEMO_TRIP_AGO_MILLIS + 20 * MINUTE, 56, null, null, ratedRangeMiles = 152f),
            )
    }
}
