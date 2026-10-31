// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.android.resources.Density
import com.dzid26.teslable.ble.BleUiState
import com.dzid26.teslable.ble.ConnectionPhase
import com.dzid26.teslable.ble.LogEntry
import com.dzid26.teslable.ble.PairingKeyStore
import com.dzid26.teslable.ble.ReadProgress
import com.dzid26.teslable.ble.TeslaAdvert
import com.dzid26.teslable.ble.TeslaConnection
import com.dzid26.teslable.ble.Vehicle
import com.dzid26.teslable.core.health.PriceChange
import com.dzid26.teslable.core.history.ActivityKind
import com.dzid26.teslable.core.history.ActivitySample
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.CurrentRest
import com.dzid26.teslable.core.history.DeepSleep
import com.dzid26.teslable.core.history.DriveSample
import com.dzid26.teslable.core.history.DriveWindow
import com.dzid26.teslable.core.history.FullRangeScale
import com.dzid26.teslable.core.history.HistoryRange
import com.dzid26.teslable.core.history.ParkedSpan
import com.dzid26.teslable.core.history.ProjectionContext
import com.dzid26.teslable.core.history.RestKind
import com.dzid26.teslable.core.history.RestStats
import com.dzid26.teslable.core.history.SleepDrop
import com.dzid26.teslable.core.units.DistanceUnit
import com.dzid26.teslable.core.units.EfficiencyUnit
import com.dzid26.teslable.health.HealthConfidence
import com.dzid26.teslable.health.HealthSummary
import com.dzid26.teslable.history.LogExportStatus
import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.ShiftStateKind
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Fast JVM snapshots of the two battery cards, light AND dark: the cars-list
 * card ([VehicleCard]) and the car-view card ([HeroCard]), one image per
 * state (fresh, stale, asleep, disconnected, reading spinner, no reading) per
 * theme. 24 PNGs total, named `<card>_<state>_light.png` /
 * `<card>_<state>_dark.png` (theme is always the last segment).
 *
 * There are no committed goldens: `recordPaparazziDebug` renders the PNGs
 * and the PR workflow diffs them before/after the pushed commit, posting
 * light by default and dark only when it differs too. Fixtures mirror the
 * unit-test patterns (a fixed NOW, the same [TeslaConnection]/[ChargeState]/
 * [VehicleStatus] shapes), and the cards get a fixed clock plus a forced
 * spinner so the images are deterministic.
 *
 * The three full screens ([ConnectionsScreen], [CarScreen], [SettingsScreen])
 * ride the same flow with one representative state each (a connected car with
 * a fresh reading and seeded history, like the old emulator set); state
 * coverage still comes from the card matrix. The screens get the same fixed
 * clock (threaded through to the cards and the history chart); everything
 * else on them is inert under Paparazzi (no-op callbacks, no back presses,
 * no store writes).
 */
class CardSnapshots {
    /**
     * One rule for every snapshot; [snapCard] and [snapScreen] switch its
     * device and rendering mode up front via `unsafeUpdateConfig` (two
     * Paparazzi rules cannot live in one class — layoutlib holds a single
     * scene per thread). Each helper sets both values every time, so the
     * renders never depend on test order.
     */
    @get:Rule
    val paparazzi = Paparazzi()

    private val defaultLocale: Locale = Locale.getDefault()

    // Chart times read the default locale; pin it so renders match across machines.
    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun vehicleCardFresh() {
        snapCard("vehicle-card_fresh") {
            VehicleCard(
                row = listRow(liveConnection(readAt = NOW - FRESH_MINUTES * MINUTE)),
                onOpen = {},
                onEditVin = {},
                onWake = {},
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun vehicleCardStale() {
        snapCard("vehicle-card_stale") {
            VehicleCard(
                row = listRow(liveConnection(readAt = NOW - STALE_MINUTES * MINUTE)),
                onOpen = {},
                onEditVin = {},
                onWake = {},
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun vehicleCardAsleep() {
        snapCard("vehicle-card_asleep") {
            VehicleCard(
                row = listRow(liveConnection(readAt = NOW - ASLEEP_MINUTES * MINUTE, status = asleep)),
                onOpen = {},
                onEditVin = {},
                onWake = {},
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun vehicleCardDisconnected() {
        snapCard("vehicle-card_disconnected") {
            VehicleCard(
                row =
                    listRow(
                        TeslaConnection(
                            address = ADDRESS,
                            name = BLE_NAME,
                            phase = ConnectionPhase.DISCONNECTED,
                        ),
                        lastKnown = storedSample(STALE_MINUTES),
                    ),
                onOpen = {},
                onEditVin = {},
                onWake = {},
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun vehicleCardSpinner() {
        snapCard("vehicle-card_spinner") {
            VehicleCard(
                row = listRow(liveConnection(readAt = NOW - FRESH_MINUTES * MINUTE, readProgress = ReadProgress(done = 2, total = 5))),
                onOpen = {},
                onEditVin = {},
                onWake = {},
                nowMillis = NOW,
                spinnerOverride = true,
            )
        }
    }

    @Test
    fun vehicleCardNoReading() {
        snapCard("vehicle-card_no-reading") {
            VehicleCard(
                row = listRow(liveConnection(charge = null)),
                onOpen = {},
                onEditVin = {},
                onWake = {},
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun heroCardFresh() {
        snapCard("hero-card_fresh") {
            HeroCard(
                connection = liveConnection(readAt = NOW - FRESH_MINUTES * MINUTE, charge = learntCharge),
                advert = advert,
                vehicle = vehicle,
                history = learntSamples,
                statusHistory = emptyList(),
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun heroCardMetric() {
        snapCard("hero-card_metric") {
            CompositionLocalProvider(LocalDistanceUnit provides DistanceUnit.KILOMETERS) {
                HeroCard(
                    connection = liveConnection(readAt = NOW - FRESH_MINUTES * MINUTE, charge = learntCharge),
                    advert = advert,
                    vehicle = vehicle,
                    history = learntSamples,
                    statusHistory = emptyList(),
                    nowMillis = NOW,
                )
            }
        }
    }

    @Test
    fun healthCardMetric() {
        snapCard("health-card_metric") {
            CompositionLocalProvider(LocalDistanceUnit provides DistanceUnit.KILOMETERS) {
                BatteryHealthCard(
                    healthSummary,
                )
            }
        }
    }

    @Test
    fun healthCardEnergyPer100() {
        snapCard("health-card_energy-per-100") {
            CompositionLocalProvider(
                LocalDistanceUnit provides DistanceUnit.KILOMETERS,
                LocalEfficiencyUnit provides EfficiencyUnit.ENERGY_PER_100_DISTANCE,
            ) {
                BatteryHealthCard(healthSummary)
            }
        }
    }

    @Test
    fun healthCardDistancePerEnergy() {
        snapCard("health-card_distance-per-energy") {
            CompositionLocalProvider(
                LocalDistanceUnit provides DistanceUnit.MILES,
                LocalEfficiencyUnit provides EfficiencyUnit.DISTANCE_PER_ENERGY,
            ) {
                BatteryHealthCard(healthSummary)
            }
        }
    }

    @Test
    fun statsCard() {
        snapCard("stats-card") {
            DrainStatsCard(restStatsFixture, CurrentRest(RestKind.POWERED_DOWN, NOW - 3 * HOUR))
        }
    }

    @Test
    fun statsCardParked() {
        snapCard("stats-card_parked") {
            DrainStatsCard(restStatsFixture, CurrentRest(RestKind.PARKED, NOW - 20 * 60_000L))
        }
    }

    @Test
    fun statsCardCharging() {
        snapCard("stats-card_charging") {
            DrainStatsCard(RestStats(), CurrentRest(RestKind.CHARGING, NOW - 45 * 60_000L))
        }
    }

    @Test
    fun statsCardDriving() {
        snapCard("stats-card_driving") {
            DrainStatsCard(restStatsFixture, CurrentRest(RestKind.DRIVING, NOW - 12 * 60_000L))
        }
    }

    @Test
    fun heroCardCharging() {
        val charging =
            learntCharge.copy(
                charging_state = ChargeState.ChargingState(Charging = Void()),
                charger_voltage = 240,
                charger_actual_current = 16,
                charge_rate_mph_float = 32.5f,
                charge_current_request = 32,
            )
        snapCard("hero-card_charging") {
            HeroCard(
                connection = liveConnection(readAt = NOW - FRESH_MINUTES * MINUTE, charge = charging),
                advert = advert,
                vehicle = vehicle,
                history = learntSamples,
                statusHistory = emptyList(),
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun heroCardChargeCost() {
        val charging =
            learntCharge.copy(
                // Level 100 while charging: the big number reads a plain 100%, not the fitted tenth.
                battery_level = 100,
                battery_range = 269.5f,
                charge_limit_soc = 100,
                charging_state = ChargeState.ChargingState(Charging = Void()),
                charger_voltage = 240,
                charger_actual_current = 16,
                charge_current_request = 32,
                charge_energy_added = 12.5f,
            )
        snapCard("hero-card_charge-cost") {
            HeroCard(
                connection = liveConnection(readAt = NOW - FRESH_MINUTES * MINUTE, charge = charging),
                advert = advert,
                vehicle = vehicle,
                history = learntSamples,
                statusHistory = emptyList(),
                nowMillis = NOW,
                pricePerKwh = 0.30f,
            )
        }
    }

    @Test
    fun heroCardStale() {
        snapCard("hero-card_stale") {
            HeroCard(
                connection = liveConnection(readAt = NOW - STALE_MINUTES * MINUTE),
                advert = advert,
                vehicle = vehicle,
                history = emptyList(),
                statusHistory = emptyList(),
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun heroCardAsleep() {
        snapCard("hero-card_asleep") {
            HeroCard(
                connection = liveConnection(readAt = NOW - ASLEEP_MINUTES * MINUTE, status = asleep),
                advert = advert,
                vehicle = vehicle,
                history = emptyList(),
                statusHistory = emptyList(),
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun heroCardDisconnected() {
        snapCard("hero-card_disconnected") {
            HeroCard(
                connection =
                    TeslaConnection(
                        address = ADDRESS,
                        name = BLE_NAME,
                        phase = ConnectionPhase.DISCONNECTED,
                        disconnectedAtMillis = NOW - STALE_MINUTES * MINUTE,
                    ),
                // A disconnected car has no live scan entry (each discovery
                // wipes the list), so no RSSI — just the disconnect age.
                advert = null,
                vehicle = vehicle,
                history = listOf(storedSample(STALE_MINUTES)),
                statusHistory = emptyList(),
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun heroCardSpinner() {
        snapCard("hero-card_spinner") {
            HeroCard(
                connection = liveConnection(readAt = NOW - FRESH_MINUTES * MINUTE, readProgress = ReadProgress(done = 2, total = 5)),
                advert = advert,
                vehicle = vehicle,
                history = emptyList(),
                statusHistory = emptyList(),
                nowMillis = NOW,
                spinnerOverride = true,
            )
        }
    }

    @Test
    fun heroCardNoReading() {
        snapCard("hero-card_no-reading") {
            HeroCard(
                connection = liveConnection(charge = null),
                advert = advert,
                vehicle = vehicle,
                history = emptyList(),
                statusHistory = emptyList(),
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun historyCardCharged() {
        snapCard("history-card_charged") {
            BatteryHistoryCard(
                samples = screenSamples,
                drives = emptyList(),
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun historyCardChargeCost() {
        snapCard("history-card_charge-cost") {
            CompositionLocalProvider(LocalPriceChanges provides listOf(PriceChange(0L, 0.20f))) {
                BatteryHistoryCard(
                    samples = screenSamples,
                    drives = emptyList(),
                    nowMillis = NOW,
                )
            }
        }
    }

    @Test
    fun historyCardGap() {
        snapCard("history-card_gap") {
            BatteryHistoryCard(
                samples = gapSamples,
                drives = emptyList(),
                nowMillis = NOW,
                initialRange = HistoryRange.ALL,
            )
        }
    }

    @Test
    fun historyCardPrecise() {
        snapCard("history-card_precise") {
            BatteryHistoryCard(
                samples = preciseSamples,
                drives = emptyList(),
                nowMillis = NOW,
                initialRange = HistoryRange.DAY,
            )
        }
    }

    @Test
    fun historyCardParkedDrain() {
        // A parked car losing a point every few hours: the "Parked" projection
        // line and its rate, no charge in sight.
        snapCard("history-card_parked-drain") {
            BatteryHistoryCard(
                samples = parkedSamples,
                drives = parkedSamples.map { parkedDrive(it) },
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun historyCardChargingProjection() {
        // Charging toward the limit: the green line up, the parked drain dashed in the measured line blue.
        snapCard("history-card_charging-projection") {
            BatteryHistoryCard(
                samples = chargingSamples,
                drives = parkedSamples.map { parkedDrive(it) },
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun historyCardChargingProjectionRtl() {
        // The same chart mirrored, on the six hour range with a wide supply swing so the beams are
        // big enough to see: the beam and the line must still meet.
        snapCard("history-card_charging-projection-rtl") {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                BatteryHistoryCard(
                    samples = wideBeamSamples,
                    drives = parkedSamples.map { parkedDrive(it) },
                    nowMillis = NOW,
                    initialRange = HistoryRange.SIX_HOURS,
                )
            }
        }
    }

    @Test
    fun historyCardParkedDrainRange() {
        // A slow parked drain (about 1.6 %/day) read on the rated range: a fitted scale turns
        // the level steps into sub-percent readings, so the line is smooth and the beam is thin.
        snapCard("history-card_parked-drain-range") {
            BatteryHistoryCard(
                samples = rangeParkedSamples,
                drives = rangeParkedSamples.map { parkedDrive(it) },
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun historyCardParkedSleep() {
        // Three short attended bursts (a read every 20 min, about 1 %/h) with the car locked and alone
        // between them (no unlocked or present status inside a gap): the line follows the sleep rate, the text
        // adds the mixed rate, and the beam reaches down to the mixed line.
        snapCard("history-card_parked-sleep") {
            BatteryHistoryCard(
                samples = sleepParkedSamples,
                drives = sleepParkedSamples.map { parkedDrive(it) },
                nowMillis = NOW,
                sleepLogs = ProjectionContext(activity = sleepActivity),
            )
        }
    }

    @Test
    fun historyCardChargingRange() {
        // The same car plugged in: the charge fit reads sub-percent too, and the beam
        // comes from the slope's own scatter plus the supply voltage.
        snapCard("history-card_charging-range") {
            BatteryHistoryCard(
                samples = rangeChargingSamples,
                drives = rangeParkedSamples.map { parkedDrive(it) },
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun historyCardChargingSixHours() {
        // The short range puts the chart's right edge only 3 h ahead, so the beam is
        // cut near the line's start: it must stay a visible sliver, not round away.
        snapCard("history-card_charging-6h") {
            BatteryHistoryCard(
                samples = chargingSamples,
                drives = parkedSamples.map { parkedDrive(it) },
                nowMillis = NOW,
                initialRange = HistoryRange.SIX_HOURS,
            )
        }
    }

    @Test
    fun historyCardFullBattery() {
        // Readings at 100%: the dots sit on the top edge and must be drawn whole.
        snapCard("history-card_full-battery") {
            BatteryHistoryCard(
                samples = fullSamples,
                drives = emptyList(),
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun historyCardChargingStalledLevel() {
        // The whole-number level has not ticked for a while but the rated range
        // climbs: the charge line still draws, from the miles.
        snapCard("history-card_charging-stalled-level") {
            BatteryHistoryCard(
                samples = stalledSamples,
                drives = parkedSamples.map { parkedDrive(it) },
                nowMillis = NOW,
            )
        }
    }

    // A pinned scale, a learned rated constant and a home price: every stat line shows.
    @Test
    fun driveCard() = snapDrive("drive-card_percent", pricePerKwh = 0.30f)

    // The cursor stands on the fourth reading, 25mi in: the percent, the distance and when it was read.
    @Test
    fun driveCardCursor() = snapDrive("drive-card_cursor", pricePerKwh = 0.30f, cursorX = 25.0)

    // Mirrored: the regen bands and the cursor must stay on their readings.
    @Test
    fun driveCardCursorRtl() = snapDrive("drive-card_cursor-rtl", pricePerKwh = 0.30f, cursorX = 25.0, rtl = true)

    // A pinned scale but no home price: the percent per distance stands in for the cost per distance.
    @Test
    fun driveCardUnpriced() = snapDrive("drive-card_unpriced")

    // No scale yet: whole-percent steps, so the used figure carries the hint and only the percent rate shows.
    @Test
    fun driveCardUnlearned() = snapDrive("drive-card_unlearned", scale = null)

    private fun snapDrive(
        name: String,
        pricePerKwh: Float? = null,
        scale: FullRangeScale? = demoTripScale,
        cursorX: Double? = null,
        // Mirrored, with the regen stretch moved to the start so a mirrored band lands far from where it belongs.
        rtl: Boolean = false,
    ) = snapCard(name) {
        val cursor = cursorX?.let { remember { CursorPin(restoredX = it) } }
        CompositionLocalProvider(
            LocalPricePerKwh provides pricePerKwh,
            LocalCurrencyCode provides "USD",
            LocalDriveCursor provides cursor,
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            DriveHistoryCard(
                samples = driveSamples(),
                drives = driveReadings(power = if (rtl) regenFirstPower else drivePower),
                initialWindow = DriveWindow.SINCE_CHARGE,
                scale = scale,
                // The rated constant is learnt along with the scale.
                ratedKwhPerMile = if (scale != null) 0.25f else null,
            )
        }
    }

    @Test
    fun screenCarsList() {
        snapScreen("screen-cars-list") {
            ConnectionsScreen(
                state = DemoScreenFixtures.listState(NOW),
                history = DemoScreenFixtures.history(NOW),
                permissionsGranted = true,
                locationServicesEnabled = true,
                locationNeeded = false,
                onRequestPermissions = {},
                onRequestLocation = {},
                onToggleScan = {},
                onToggleTracking = {},
                onOpen = { _, _ -> },
                onEditVin = {},
                onWake = {},
                onOpenSettings = {},
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun screenCarDetail() {
        snapScreen("screen-car-detail") {
            CarScreen(
                state = DemoScreenFixtures.carState(NOW),
                vehicle = DemoScreenFixtures.vehicle,
                advert = DemoScreenFixtures.carAdvert,
                address = DemoScreenFixtures.DEMO_ADDRESS,
                history = DemoScreenFixtures.history(NOW),
                driveHistory = DemoScreenFixtures.drives(NOW),
                statusHistory = emptyList(),
                onBack = {},
                onEdit = {},
                onPair = {},
                onRefresh = {},
                onOpenSettings = {},
                onToggleTracking = {},
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun exportLogsCardIdle() = snapCard("export-logs-card_idle") { ExportLogsCardBody(LogExportStatus.Idle) {} }

    @Test
    fun exportLogsCardRunning() = snapCard("export-logs-card_running") { ExportLogsCardBody(LogExportStatus.Running) {} }

    @Test
    fun exportLogsCardDone() = snapCard("export-logs-card_done") { ExportLogsCardBody(LogExportStatus.Done(116_353)) {} }

    @Test
    fun exportLogsCardFailed() = snapCard("export-logs-card_failed") { ExportLogsCardBody(LogExportStatus.Failed(leftover = true)) {} }

    @Test
    fun screenSettings() {
        // The export button asks for an activity result launcher, which Paparazzi has no activity for.
        val registryOwner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry =
                    object : ActivityResultRegistry() {
                        override fun <I, O> onLaunch(
                            requestCode: Int,
                            contract: ActivityResultContract<I, O>,
                            input: I,
                            options: ActivityOptionsCompat?,
                        ) = Unit
                    }
            }
        snapScreen("screen-settings") {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                SettingsScreen(
                    keyStore = PairingKeyStore(LocalContext.current),
                    vehicles = listOf(DemoScreenFixtures.vehicle),
                    onClearPairingCache = {},
                    logExportStatus = LogExportStatus.Idle,
                    onExportLogs = {},
                    onOpenAbout = {},
                    onBack = {},
                    versionName = DemoScreenFixtures.VERSION_NAME,
                )
            }
        }
    }

    /**
     * One state, three images: the card in the fixed light scheme and in the
     * fixed dark scheme, plus a narrow Jelly Star render in light, all
     * SHRINK-wrapped so each bitmap hugs the card (full-width x
     * content-height, no black void). The theme is always the last name
     * segment, so the workflow can group by state.
     */
    private fun snapCard(
        name: String,
        content: @Composable () -> Unit,
    ) = paparazzi.snapCard(name, content)

    @Test
    fun screenCarDetailLearnt() {
        // Three charge sessions and a climb through 40-70% read in quarter
        // steps: the health card is past Learning and shows the fitted full
        // range, capacity and the rated constant.
        snapScreen("screen-car-detail-learnt") {
            CarScreen(
                state = learntState,
                vehicle = vehicle,
                advert = advert,
                address = ADDRESS,
                history = learntSamples,
                driveHistory = emptyList(),
                statusHistory = emptyList(),
                onBack = {},
                onEdit = {},
                onPair = {},
                onRefresh = {},
                onOpenSettings = {},
                onToggleTracking = {},
                nowMillis = NOW,
            )
        }
    }

    /**
     * One screen, two images: the full screen in NORMAL mode on a 1080x2400
     * phone at 420 dpi (the old emulator's size, 411x914 dp), so the PNGs
     * match the website images and scrolled content gets vertical room.
     * Light and dark.
     */
    private fun snapScreen(
        name: String,
        content: @Composable () -> Unit,
    ) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = screenPhone,
            renderingMode = SessionParams.RenderingMode.NORMAL,
        )
        paparazzi.snapshot(name + "_light") {
            ScreenFrame(darkTheme = false, content = content)
        }
        paparazzi.snapshot(name + "_dark") {
            ScreenFrame(darkTheme = true, content = content)
        }
    }

    companion object {
        internal const val NOW = 1_760_000_000_000L
        internal const val MINUTE = 60_000L
        private const val HOUR = 3_600_000L
        internal const val FRESH_MINUTES = 1L
        private const val ASLEEP_MINUTES = 3L
        private const val STALE_MINUTES = 12L
        private const val LIVE_PERCENT = 62
        private const val STORED_PERCENT = 78
        private const val CHARGE_LIMIT = 80
        private const val LIVE_RANGE_MILES = 212.5f
        private const val STORED_RANGE_MILES = 245.5f
        private const val ADDRESS = "AA:BB:CC:DD:EE:01"
        private const val BLE_NAME = "S1f094173C"

        internal fun liveConnection(
            readAt: Long? = null,
            status: VehicleStatus? = awake,
            charge: ChargeState? = liveCharge,
            readProgress: ReadProgress? = null,
        ) = TeslaConnection(
            address = ADDRESS,
            name = BLE_NAME,
            phase = ConnectionPhase.READY,
            status = status,
            charge = charge,
            chargeAtMillis = readAt,
            readProgress = readProgress,
        )

        private fun listRow(
            connection: TeslaConnection?,
            lastKnown: BatterySample? = null,
        ) = VehicleRow(
            bleName = BLE_NAME,
            address = ADDRESS,
            title = vehicle.title,
            vehicle = vehicle,
            connection = connection,
            advert = advert,
            lastKnown = lastKnown,
        )

        private fun storedSample(readMinutesAgo: Long) =
            BatterySample(
                timestampMillis = NOW - readMinutesAgo * MINUTE,
                batteryLevel = STORED_PERCENT,
                chargingState = null,
                chargeLimit = null,
                vehicleId = BLE_NAME,
                ratedRangeMiles = STORED_RANGE_MILES,
                readAtMillis = NOW - readMinutesAgo * MINUTE,
            )

        private fun historySample(
            hoursAgo: Long,
            percent: Int,
        ) = BatterySample(
            timestampMillis = NOW - hoursAgo * HOUR,
            batteryLevel = percent,
            chargingState = null,
            chargeLimit = null,
            vehicleId = BLE_NAME,
            readAtMillis = NOW - hoursAgo * HOUR,
        )

        private fun parkedDrive(sample: BatterySample) =
            DriveSample(
                timestampMillis = sample.timestampMillis + MINUTE,
                vehicleId = BLE_NAME,
                shiftState = ShiftStateKind.P,
                speed = 0,
                power = 0,
                odometerInHundredthsOfAMile = ODOMETER,
            )

        private fun projectionSample(
            minutesAgo: Long,
            percent: Int,
            charging: Boolean,
            ratedMiles: Float,
        ) = BatterySample(
            timestampMillis = NOW - minutesAgo * MINUTE,
            batteryLevel = percent,
            chargingState = if (charging) ChargingStateKind.Charging else null,
            chargeLimit = if (charging) CHARGE_LIMIT else null,
            vehicleId = BLE_NAME,
            ratedRangeMiles = ratedMiles,
            readAtMillis = NOW - minutesAgo * MINUTE,
        )

        private const val ODOMETER = 1_234_500

        /** A reading on a 300 mi car (3 mi per point): the level rounds, the range does not. */
        private fun rangeSample(
            minutesAgo: Long,
            soc: Double,
            charging: Boolean = false,
            volts: Int? = null,
        ) = BatterySample(
            timestampMillis = NOW - minutesAgo * MINUTE,
            batteryLevel = Math.round(soc).toInt(),
            chargingState = if (charging) ChargingStateKind.Charging else null,
            chargeLimit = if (charging) CHARGE_LIMIT else null,
            vehicleId = BLE_NAME,
            ratedRangeMiles = (soc * 3.0).toFloat(),
            chargerVoltage = volts,
            readAtMillis = NOW - minutesAgo * MINUTE,
        )

        // Two days of driving down from 85 to 68 %, hourly, before the day shown: it gives
        // the scale fit its level steps, as a real history does.
        private val rangeDrivenSamples =
            (0..47).map { step ->
                rangeSample(1500L + (47 - step) * 60L, 68.0 + (85.0 - 68.0) * (47 - step) / 47.0)
            }

        // Awake bursts at these minutes ago (three reads each), asleep in between.
        private val sleepBursts = listOf(1260L, 800L, 300L, 0L)

        // The level after each read, walking forward in time: an awake pair (20 min) loses
        // 0.3 points, a pair across a sleeping gap loses 0.1 whatever its length.
        private val sleepParkedSamples: List<BatterySample> =
            run {
                var soc = 68.0
                val reads =
                    sleepBursts.flatMapIndexed { burst, start ->
                        if (burst == sleepBursts.lastIndex) listOf(start) else listOf(start, start - 20, start - 40)
                    }
                // The earlier driving gives the scale fit its level steps.
                rangeDrivenSamples +
                    reads.mapIndexed { index, minutesAgo ->
                        if (index > 0) soc -= if (reads[index - 1] - minutesAgo == 20L) 0.3 else 0.1
                        rangeSample(minutesAgo, soc)
                    }
            }

        // The climate runs mid-way through each awake burst and stays off in the gaps. The gaps are the sleep
        // the dashed line follows.
        private val sleepActivity =
            sleepBursts.dropLast(1).flatMap { from ->
                listOf(from - 10, from - 30).map {
                    ActivitySample(NOW - it * MINUTE, BLE_NAME, ActivityKind.CLIMATE, on = true)
                }
            }

        // 21 h parked, 68 -> 66.6 % (about 1.6 %/day), a read every 20 min.
        private val rangeParkedSamples =
            rangeDrivenSamples +
                (0..63).map { step ->
                    rangeSample((63 - step) * 20L, 66.6 + (68.0 - 66.6) * (63 - step) / 63.0)
                }

        // Then a charge from 67 -> ~73 % over the last 4 h, a read every 10 min, supply 218-243 V.
        private val rangeChargingSamples =
            rangeParkedSamples.dropLast(12) +
                (0..23).map { step ->
                    val minutesAgo = (23 - step) * 10L
                    val volts = listOf(243, 238, 226, 218, 231, 240)[step % 6]
                    rangeSample(minutesAgo, 66.95 + step * 0.25, charging = true, volts = volts)
                }

        private val parkedSamples =
            listOf(
                projectionSample(20 * 60L, 60, charging = false, ratedMiles = 180f),
                projectionSample(16 * 60L, 59, charging = false, ratedMiles = 177f),
                projectionSample(12 * 60L, 58, charging = false, ratedMiles = 174f),
                projectionSample(8 * 60L, 57, charging = false, ratedMiles = 171f),
                projectionSample(4 * 60L, 56, charging = false, ratedMiles = 168f),
                projectionSample(0L, 55, charging = false, ratedMiles = 165f),
            )

        // The AC supply sagging and recovering around 240 V: the beam around the green line.
        private val chargingSamples =
            parkedSamples.dropLast(1) +
                listOf(
                    projectionSample(60L, 55, charging = true, ratedMiles = 165f).copy(chargerVoltage = 245),
                    projectionSample(30L, 58, charging = true, ratedMiles = 174f).copy(chargerVoltage = 215),
                    projectionSample(0L, 61, charging = true, ratedMiles = 183f).copy(chargerVoltage = 232),
                )

        private val wideBeamSamples =
            chargingSamples.map { sample ->
                sample.chargerVoltage?.let { sample.copy(chargerVoltage = 232 + (it - 232) * 3) } ?: sample
            }

        private val fullSamples =
            listOf(
                projectionSample(20 * 60L, 98, charging = false, ratedMiles = 294f),
                projectionSample(12 * 60L, 99, charging = true, ratedMiles = 297f),
                projectionSample(8 * 60L, 100, charging = false, ratedMiles = 300f),
                projectionSample(4 * 60L, 100, charging = false, ratedMiles = 300f),
                projectionSample(0L, 100, charging = false, ratedMiles = 300f),
            )

        private val stalledSamples =
            parkedSamples.dropLast(1) +
                listOf(
                    projectionSample(30L, 55, charging = true, ratedMiles = 165f).copy(chargerVoltage = 215),
                    projectionSample(15L, 55, charging = true, ratedMiles = 166.5f).copy(chargerVoltage = 245),
                    projectionSample(0L, 55, charging = true, ratedMiles = 168f).copy(chargerVoltage = 232),
                )

        private fun chargeSample(
            minutesAgo: Long,
            percent: Int,
            energyAddedKwh: Float?,
            milesAddedRated: Float?,
            chargingState: ChargingStateKind?,
        ) = BatterySample(
            timestampMillis = NOW - minutesAgo * MINUTE,
            batteryLevel = percent,
            chargingState = chargingState,
            chargeLimit = null,
            vehicleId = BLE_NAME,
            chargeEnergyAdded = energyAddedKwh,
            chargeMilesAddedRated = milesAddedRated,
            readAtMillis = NOW - minutesAgo * MINUTE,
        )

        private val liveCharge =
            ChargeState(
                battery_level = LIVE_PERCENT,
                battery_range = LIVE_RANGE_MILES,
                charge_limit_soc = CHARGE_LIMIT,
            )
        private val awake =
            VehicleStatus(
                vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED,
                vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE,
                userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT,
            )
        private val asleep = awake.copy(vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP)
        internal val vehicle =
            Vehicle(
                bleName = BLE_NAME,
                address = ADDRESS,
                gattName = "Model 3",
                displayName = "Model 3",
                // Enrolled, so the app-key card stays out of the car screen.
                keySlot = 3,
            )
        private val healthSummary =
            HealthSummary(
                sessions = 6,
                learningTarget = 3,
                sohPercent = 89f,
                capacityKwh = 57.2f,
                ratedKwhPerMile = 0.2736f,
                capacitySwingPercent = null,
                fullRangeMiles = 262.4f,
                fullRangeSteps = 42,
                confidence = HealthConfidence.MEDIUM,
                qualityNote = null,
                newCapacityKwh = 75f,
            )

        // 28 h parked (1.1 points lost), 22 h of it locked and alone, and a 9 h 25 min deep sleep two days back.
        private val restStatsFixture =
            RestStats(
                lowestDrain = ParkedSpan(startMillis = NOW - 30 * HOUR, endMillis = NOW - 13 * HOUR, dropPercent = 0.03),
                parked = ParkedSpan(startMillis = NOW - 40 * HOUR, endMillis = NOW - 12 * HOUR, dropPercent = 1.1),
                powerDown = ParkedSpan(startMillis = NOW - 38 * HOUR, endMillis = NOW - 16 * HOUR, dropPercent = 0.7),
                deepSleep = DeepSleep(NOW - 34 * HOUR, NOW - 34 * HOUR + 565 * MINUTE, SleepDrop(51.3, 50.9, 700 * MINUTE, ceiling = true)),
            )

        internal val advert = TeslaAdvert(name = BLE_NAME, address = ADDRESS, rssi = -70)

        private val screenState =
            BleUiState(
                trackingEnabled = true,
                devices = listOf(advert),
                connections = mapOf(ADDRESS to liveConnection(readAt = NOW - FRESH_MINUTES * MINUTE)),
                vehicles = listOf(vehicle),
                log = listOf(LogEntry(vehicleId = BLE_NAME, message = "$BLE_NAME · Connected · 62%")),
            )

        // The live reading agrees with the learnt 270-mile scale: 62.4% is
        // 168.5 rated miles, which the fit shows as 62.4% against the int 62.
        internal val learntCharge =
            ChargeState(
                battery_level = LIVE_PERCENT,
                battery_range = 168.5f,
                charge_limit_soc = CHARGE_LIMIT,
            )
        private val learntState =
            screenState.copy(
                connections =
                    mapOf(
                        ADDRESS to
                            liveConnection(
                                readAt = NOW - FRESH_MINUTES * MINUTE,
                                charge =
                                    ChargeState(
                                        battery_level = LIVE_PERCENT,
                                        battery_range = 168.5f,
                                        charge_limit_soc = CHARGE_LIMIT,
                                    ),
                            ),
                    ),
            )

        // Drained to 68%, then one backbone charge (68 → 78%, a 10% swing
        // with energy and rated-miles growth) so the health card renders
        // populated — usable capacity + full range from the session's rated
        // constant — while honestly staying Learning at 1 of 3 sessions.
        // No charge limit, so the chart shows the run with no projection;
        // the Complete + parked tail ends the session for the stats line.
        private val screenSamples =
            listOf(
                historySample(6L, 72),
                historySample(4L, 70),
                historySample(2L, 68),
                chargeSample(50L, 68, 1.0f, 4.0f, ChargingStateKind.Charging),
                chargeSample(35L, 73, 3.2f, 13.0f, ChargingStateKind.Charging),
                chargeSample(20L, 78, 5.5f, 22.0f, ChargingStateKind.Charging),
                chargeSample(15L, 78, null, null, ChargingStateKind.Complete),
                historySample(0L, 77),
            )

        // A rated-range climb on a 270-mile scale (0.25% steps, the level
        // rounds the true SOC) with three charges of 10% each: enough steps
        // for the scale to pin and enough sessions to leave Learning.
        internal val learntSamples =
            (0..120)
                .map { step ->
                    val soc = 40.1f + step * 0.25f
                    BatterySample(
                        timestampMillis = NOW - (200L - step) * MINUTE,
                        batteryLevel = Math.round(soc),
                        chargingState = null,
                        chargeLimit = null,
                        vehicleId = BLE_NAME,
                        ratedRangeMiles = 2.7f * soc,
                        readAtMillis = NOW - (200L - step) * MINUTE,
                    )
                }.plus(
                    (0..2)
                        .flatMap { n ->
                            val base = 60L * (n + 2) * 24 // one charge each of the days before
                            listOf(
                                chargeSample(base + 30, 50, 0f, 0f, ChargingStateKind.Charging),
                                chargeSample(base + 15, 55, 3.0f, 13.5f, ChargingStateKind.Charging),
                                chargeSample(base, 60, 6.0f, 27f, ChargingStateKind.Charging),
                                chargeSample(base - 5, 60, null, null, ChargingStateKind.Complete),
                            )
                        },
                ).sortedBy { it.timestampMillis }

        // The rated-range climb above pins the scale (270 mi, 2.7 mi per percent),
        // then a charge ending at 70.1% and a parked drain to 69.3%: 0.8 points
        // used, where the whole levels (70 -> 69) would say 1.
        private val preciseSamples =
            learntSamples.filter { it.chargingState == null } +
                listOf(
                    preciseSample(60L, 70, 70.1f, ChargingStateKind.Charging),
                    preciseSample(30L, 70, 69.6f, ChargingStateKind.Disconnected),
                    preciseSample(5L, 69, 69.3f, ChargingStateKind.Disconnected),
                )

        private fun preciseSample(
            minutesAgo: Long,
            level: Int,
            soc: Float,
            state: ChargingStateKind,
        ) = BatterySample(
            timestampMillis = NOW - minutesAgo * MINUTE,
            batteryLevel = level,
            chargingState = state,
            chargeLimit = null,
            vehicleId = BLE_NAME,
            ratedRangeMiles = 2.7f * soc,
            readAtMillis = NOW - minutesAgo * MINUTE,
        )

        // A 40-mile drive five hours ago, parked before and after: rated
        // range falls 230 -> 190 mi while the estimate runs a little lower,
        // with a regenerating read at the start and two regenerating reads on the way down a hill that held the
        // rated range.
        private val driveMinutes = listOf(300L, 290L, 280L, 270L, 260L, 250L, 240L)
        private val driveOdometers = listOf(1_200_000, 1_200_000, 1_201_200, 1_202_500, 1_203_400, 1_204_000, 1_204_000)
        private val drivePower = listOf(0, -3, 10, -18, -6, 0, 0)
        private val driveShifts =
            listOf(
                ShiftStateKind.P,
                ShiftStateKind.D,
                ShiftStateKind.D,
                ShiftStateKind.D,
                ShiftStateKind.D,
                ShiftStateKind.P,
                ShiftStateKind.P,
            )
        private val driveRated = listOf(230f, 229f, 229f, 213f, 213f, 190f, 190f)

        private fun driveSamples(
            minutesEarlier: Long = 0L,
            rangeOffset: Float = 0f,
        ) = driveMinutes.mapIndexed { index, minutesAgo ->
            val at = NOW - (minutesAgo + minutesEarlier) * MINUTE
            val rated = driveRated[index] + rangeOffset
            BatterySample(
                timestampMillis = at,
                batteryLevel = (rated / 3.1f).toInt(),
                chargingState = ChargingStateKind.Disconnected,
                chargeLimit = null,
                vehicleId = BLE_NAME,
                ratedRangeMiles = rated,
                readAtMillis = at,
            )
        }

        private val regenFirstPower = listOf(0, -25, -20, 10, 30, 0, 0)

        private fun driveReadings(
            minutesEarlier: Long = 0L,
            power: List<Int> = drivePower,
        ) = driveMinutes.mapIndexed { index, minutesAgo ->
            DriveSample(
                timestampMillis = NOW - (minutesAgo + minutesEarlier) * MINUTE + 1_000,
                vehicleId = BLE_NAME,
                shiftState = driveShifts[index],
                speed = null,
                power = power[index],
                odometerInHundredthsOfAMile = driveOdometers[index],
            )
        }

        // A charge five days before the newest reading, with nothing recorded
        // in between: the stats must name the charge (47%, five days ago) and
        // the drain since (19%), not the first reading after it. This is the
        // gap case the charge-stats anchor fix covers.
        private val gapSamples =
            listOf(
                historySample(6L * 24, 44),
                chargeSample(5L * 24 * 60 + 2, 46, 30f, 120f, ChargingStateKind.Charging),
                chargeSample(5L * 24 * 60, 47, 32f, 128f, ChargingStateKind.Charging),
                historySample(0L, 28),
            )
    }
}

/**
 * The snapshot frame: a fixed light or dark scheme (never the dynamic color,
 * which Paparazzi has no real context for) plus a surface, so the cards
 * render exactly as they would on a matching phone screen.
 */
@Composable
internal fun CardFrame(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalIs24Hour provides true, LocalInspectionMode provides true) {
        MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
            Surface {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(all = 16.dp),
                ) {
                    content()
                }
            }
        }
    }
}

/** The 1080x2400 phone at 420 dpi of the full-screen renders (the old emulator's size, 411x914 dp). */
internal val screenPhone =
    DeviceConfig.NEXUS_5.copy(
        screenWidth = 1080,
        screenHeight = 2400,
        xdpi = 420,
        ydpi = 420,
        density = Density.create(DENSITY_DPI),
    )
private const val DENSITY_DPI = 420

/**
 * The full-screen frame: the seeded blue Material You scheme (see
 * [demoColorScheme]) between fake system bars, so the render reads like the
 * phone screenshots.
 */
@Composable
internal fun ScreenFrame(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalIs24Hour provides true, LocalInspectionMode provides true) {
        DemoSystemBars(darkTheme = darkTheme, content = content)
    }
}
