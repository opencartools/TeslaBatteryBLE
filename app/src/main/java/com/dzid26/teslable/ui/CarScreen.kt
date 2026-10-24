// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.EnergyPriceStore
import com.dzid26.teslable.R
import com.dzid26.teslable.UnitStore
import com.dzid26.teslable.ble.BatteryPercent
import com.dzid26.teslable.ble.BleUiState
import com.dzid26.teslable.ble.ConnectionDisplay
import com.dzid26.teslable.ble.ConnectionPhase
import com.dzid26.teslable.ble.TeslaAdvert
import com.dzid26.teslable.ble.TeslaConnection
import com.dzid26.teslable.ble.Vehicle
import com.dzid26.teslable.ble.ageLabel
import com.dzid26.teslable.ble.batteryPercent
import com.dzid26.teslable.ble.chargingStateText
import com.dzid26.teslable.ble.connectionDisplay
import com.dzid26.teslable.ble.vehicleStatusText
import com.dzid26.teslable.core.health.PriceChange
import com.dzid26.teslable.core.health.chargeSessionOpen
import com.dzid26.teslable.core.health.lastFinishedChargeStats
import com.dzid26.teslable.core.health.priceForCharge
import com.dzid26.teslable.core.history.ActivitySample
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.ChargeProjection
import com.dzid26.teslable.core.history.ChargeStats
import com.dzid26.teslable.core.history.DischargeProjection
import com.dzid26.teslable.core.history.DriveSample
import com.dzid26.teslable.core.history.FullRangeScale
import com.dzid26.teslable.core.history.HistoryRange
import com.dzid26.teslable.core.history.ProjectionContext
import com.dzid26.teslable.core.history.RouteSample
import com.dzid26.teslable.core.history.StatusSample
import com.dzid26.teslable.core.history.TENTHS_PER_PERCENT
import com.dzid26.teslable.core.history.Trip
import com.dzid26.teslable.core.history.activeRoute
import com.dzid26.teslable.core.history.chargeProjection
import com.dzid26.teslable.core.history.compactDuration
import com.dzid26.teslable.core.history.currentRest
import com.dzid26.teslable.core.history.dischargeProjection
import com.dzid26.teslable.core.history.markingLive
import com.dzid26.teslable.core.history.projectionHorizonMillis
import com.dzid26.teslable.core.history.restStats
import com.dzid26.teslable.core.history.socTenths
import com.dzid26.teslable.core.history.statusDurations
import com.dzid26.teslable.core.history.within
import com.dzid26.teslable.core.units.DistanceUnit
import com.dzid26.teslable.core.vehicle.decodeVin
import com.dzid26.teslable.health.healthSummary
import com.dzid26.teslable.history.FullRangeScales
import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.PairingPhase
import com.dzid26.teslable.protocol.asleep
import com.dzid26.teslable.protocol.chargingMph
import com.dzid26.teslable.protocol.chargingStateKind
import com.tesla.generated.carserver.vehicle.ChargeState
import kotlinx.coroutines.delay
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

// -------------------------------------------------------------------- car view

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CarScreen(
    state: BleUiState,
    vehicle: Vehicle?,
    advert: TeslaAdvert?,
    address: String,
    history: List<BatterySample>,
    driveHistory: List<DriveSample>,
    statusHistory: List<StatusSample>,
    // Sentry and climate readings: what keeps a locked car from counting as asleep.
    activityHistory: List<ActivitySample> = emptyList(),
    // The newest route-bearing drive reading per car, for the live trip card.
    routes: Map<String, RouteSample> = emptyMap(),
    // The finished navigations per car, for the past-trips card.
    trips: Map<String, List<Trip>> = emptyMap(),
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onPair: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleTracking: (Boolean) -> Unit,
    // Injectable for the Paparazzi snapshots; production call sites use the
    // default, which keeps the same ticking clocks as before.
    nowMillis: Long? = null,
    // Injectable for the Paparazzi snapshots; null reads the owner's saved home price.
    homePrice: Float? = null,
) {
    BackHandler { onBack() }
    val connection = state.connections[address]
    // Filtered once per change of the source log, not per recomposition: the state re-emits on every
    // RSSI poll and log line, and a fresh list each time would redo every derived figure below and
    // redraw every card that takes the list.
    val bleName = vehicle?.bleName
    val vehicleHistory = remember(history, bleName) { history.filter { it.vehicleId == bleName } }
    val vehicleDrives = remember(driveHistory, bleName) { driveHistory.filter { it.vehicleId == bleName } }
    val vehicleStatuses = remember(statusHistory, bleName) { statusHistory.filter { it.vehicleId == bleName } }
    val vehicleActivity = remember(activityHistory, bleName) { activityHistory.filter { it.vehicleId == bleName } }
    val paired = isPaired(connection, vehicle)
    // Fitted once for every card below: the hero, the trip, both history charts.
    val scale = remember(vehicleHistory) { FullRangeScales.of(vehicleHistory) }
    // The stats card looks at the whole log, whatever range the battery chart shows.
    // The status and activity logs the sleep rules read, built once per change of either.
    val sleepLogs = remember(vehicleStatuses, vehicleActivity) { ProjectionContext(statuses = vehicleStatuses, activity = vehicleActivity) }
    // The whole-log figures run off the main thread; each card shows once its first result is in.
    val rest =
        rememberInBackground(null, vehicleHistory, vehicleDrives, sleepLogs, scale) {
            restStats(vehicleHistory, vehicleDrives, sleepLogs.copy(scale = scale))
        }
    val statsNow = nowMillis ?: rememberNowMillis()
    val current =
        rememberInBackground(null, vehicleHistory, vehicleDrives, sleepLogs, statsNow) {
            currentRest(vehicleHistory, vehicleDrives, statsNow, sleepLogs)
        }
    val factoryRangeMiles = vehicle?.factoryRangeMiles
    val health =
        rememberInBackground(null, vehicleHistory, factoryRangeMiles) {
            healthSummary(vehicleHistory, epaRatedRangeMiles = factoryRangeMiles)
        }
    // What a pull on this screen will do, and the feedback while it runs.
    val pullLabel =
        when {
            connection?.phase != ConnectionPhase.READY -> "Reconnect"
            connection.status?.asleep != false -> "Wake car"
            else -> "Read battery"
        }
    val refreshingLabel =
        when {
            connection?.phase != ConnectionPhase.READY -> "Reconnecting…"
            connection.status?.asleep != false -> "Waking…"
            else -> "Reading…"
        }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(REFRESH_FEEDBACK_MS)
            refreshing = false
        }
    }
    Scaffold(
        modifier = Modifier.countingTouches(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "All cars",
                        )
                    }
                },
                title = {
                    val vinLabel = remember(vehicle?.vin) { vehicle?.vin?.let(::decodeVin)?.label }
                    Row(verticalAlignment = Alignment.Top) {
                        Column {
                            Text(vehicle?.title ?: connection?.gattDeviceName ?: advert?.name ?: "Car")
                            vinLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                        if (vehicle != null) {
                            // A small glyph lifted onto the name line, inside the standard 48dp touch target.
                            IconButton(onClick = onEdit, modifier = Modifier.offset(y = EDIT_ICON_LIFT)) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = stringResource(R.string.car_edit_title),
                                    modifier = Modifier.size(EDIT_ICON),
                                )
                            }
                        }
                    }
                },
                actions = {
                    TrackingToggle(
                        checked = state.trackingEnabled,
                        onCheckedChange = onToggleTracking,
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                onRefresh()
            },
            state = pullState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            indicator = {
                RefreshPill(
                    state = pullState,
                    isRefreshing = refreshing,
                    pullLabel = pullLabel,
                    refreshingLabel = refreshingLabel,
                )
            },
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val context = LocalContext.current
                val priceChanges =
                    remember { homePrice?.let { listOf(PriceChange(0L, it)) } ?: EnergyPriceStore(context).priceChanges() }
                val pricePerKwh = priceChanges.lastOrNull()?.pricePerKwh
                val currencyCode = remember { EnergyPriceStore(context).currencyCode() }
                val unitStore = remember { UnitStore(context) }
                val distanceUnit = remember { unitStore.preference().resolve() }
                val efficiencyUnit = remember { unitStore.efficiency() }
                CompositionLocalProvider(
                    LocalDistanceUnit provides distanceUnit,
                    LocalEfficiencyUnit provides efficiencyUnit,
                    LocalCurrencyCode provides currencyCode,
                    LocalPricePerKwh provides pricePerKwh,
                    LocalPriceChanges provides priceChanges,
                ) {
                    HeroCard(
                        pricePerKwh = pricePerKwh,
                        bluetoothOn = state.bluetoothOn,
                        connection = connection,
                        advert = advert,
                        vehicle = vehicle,
                        history = vehicleHistory,
                        statusHistory = vehicleStatuses,
                        nowMillis = nowMillis ?: rememberNowMillis(connection?.chargeAtMillis),
                        scale = scale,
                    )
                    // Once the app key is enrolled the hero carries its status; the
                    // card only exists for pairing, so it disappears when there is
                    // nothing to do.
                    if (!paired) {
                        KeyCard(connection, vehicle, onPair)
                    }
                    val liveRoute = activeRoute(vehicle?.let { routes[it.bleName] }, nowMillis ?: rememberNowMillis())
                    TripSection(
                        route = liveRoute,
                        currentRatedMiles = vehicleHistory.lastOrNull()?.ratedRangeMiles,
                        scale = scale,
                        ratedKwhPerMile = health?.ratedKwhPerMile,
                        pricePerKwh = pricePerKwh,
                    )
                    BatteryHistoryCard(
                        vehicleHistory,
                        vehicleDrives,
                        // The ticking clock, not a fresh wall-clock read: that changed on every recomposition (each
                        // RSSI poll), so the card redid its projections and redrew its chart every time.
                        nowMillis = nowMillis ?: rememberNowMillis(),
                        sleepLogs = sleepLogs,
                    )
                    DriveHistoryCard(vehicleHistory, vehicleDrives, scale = scale, ratedKwhPerMile = health?.ratedKwhPerMile)
                    val vehicleTrips = vehicle?.let { trips[it.bleName] }.orEmpty()
                    PastTripsCard(
                        trips = remember(vehicleTrips, liveRoute) { vehicleTrips.markingLive(liveRoute) },
                        samples = vehicleHistory,
                        scale = scale,
                        ratedKwhPerMile = health?.ratedKwhPerMile,
                        pricePerKwh = pricePerKwh,
                    )
                    health?.let { BatteryHealthCard(it) }
                    rest?.let { DrainStatsCard(it, current) }
                }
            }
        }
    }
}

@Composable
internal fun HeroCard(
    connection: TeslaConnection?,
    advert: TeslaAdvert?,
    vehicle: Vehicle?,
    history: List<BatterySample>,
    statusHistory: List<StatusSample>,
    bluetoothOn: Boolean = true,
    // Injectable for the Paparazzi snapshots; production call sites use the
    // default, which is the same ticking clock as before.
    nowMillis: Long = rememberNowMillis(connection?.chargeAtMillis),
    // Injectable for the Paparazzi snapshots; null keeps the timed spinner.
    spinnerOverride: Boolean? = null,
    // The owner's price per kWh, for the session cost; null shows none.
    pricePerKwh: Float? = null,
    // The fitted full-range scale; the car screen fits it once for all its cards.
    scale: FullRangeScale? = remember(history) { FullRangeScales.of(history) },
) {
    val context = LocalContext.current
    val display = connectionDisplay(connection, advert, vehicle = vehicle, bluetoothOn = bluetoothOn)
    val charge = connection?.charge
    // With no live reading, the newest stored sample still answers "how full
    // is the car?" at a glance; the caption makes its age explicit.
    val lastKnown = history.lastOrNull()
    val reading = batteryPercent(connection, lastKnown, nowMillis)
    // Rated miles are the internal unit; SOC is derived at the edge from the
    // fitted scale (null until it pins, then the int level shows).
    val vehicleId = vehicle?.bleName.orEmpty()
    val durations = rememberInBackground(null, statusHistory, vehicleId, nowMillis) { statusDurations(statusHistory, vehicleId, nowMillis) }
    val keySlot = connection?.keySlot ?: vehicle?.keySlot
    val pairing = connection?.pairing ?: PairingPhase.IDLE
    Card(
        modifier =
            Modifier
                .fillMaxWidth(),
    ) {
        Box {
            Column(Modifier.padding(16.dp)) {
                // The pill sits on the title's line: its bottom meets the
                // title's, so it reads as one header, clear of the spinner.
                // Everything else spans the full width below, so a narrow
                // screen wraps the status lines instead of squeezing them
                // beside the pill.
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = display.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    StatusPill(
                        connection = connection,
                        bluetoothOn = bluetoothOn,
                        onAsleepClick = {
                            Toast
                                .makeText(context, "Swipe down to wake the car", Toast.LENGTH_SHORT)
                                .show()
                        },
                    )
                }
                // The app key state sits above the connection state: it is
                // what unlocks authenticated reads.
                if (isPaired(connection, vehicle)) {
                    Text(
                        text = keySlot?.let { "App key paired · slot\u00A0$it" } ?: "App key paired",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = heroStatusText(display, connection?.disconnectedAtMillis, nowMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The vehicle statuses sit right under the connection state,
                // so the block reads as one unit above the percentage.
                connection?.status?.let { status ->
                    Text(vehicleStatusText(status, durations), style = MaterialTheme.typography.bodySmall)
                }
                if (pairing == PairingPhase.OK) {
                    // Only shown for the pairing that just succeeded; the
                    // Tesla screen calls the key Phone Key, so this hint does.
                    Text(
                        text = "Rename the Phone Key in Controls > Locks on the Tesla screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                SocBlock(
                    reading = reading,
                    live = charge?.battery_level != null,
                    rangeMiles = charge?.battery_range ?: lastKnown?.ratedRangeMiles,
                    scale = scale,
                    stateText = display.stateText,
                    connection = connection,
                    nowMillis = nowMillis,
                )
                Spacer(Modifier.height(8.dp))
                // The session's kWh and cost stay up top while it runs; once it ends they move to the history card's Last charge line.
                val sessionOpen =
                    chargeSessionOpen(
                        charge?.chargingStateKind,
                        history.lastOrNull { it.isCharging }?.timestampMillis,
                        nowMillis,
                    )
                ChargeDetails(charge, pricePerKwh, showSession = sessionOpen)
            }
            // The card's top-right corner, clear of the title and the pill: a spinner while a
            // read is in flight.
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ReadingSpinner(connection, forceVisible = spinnerOverride)
            }
        }
    }
}

/** The car-view status line: a disconnected car reads "Disconnected 12m". */
private fun heroStatusText(
    display: ConnectionDisplay,
    disconnectedAtMillis: Long?,
    nowMillis: Long,
): String {
    val offlineFor =
        disconnectedAtMillis
            ?.takeIf { display.stateText == "Disconnected" }
            ?.let { compactDuration(nowMillis - it) }
    return offlineFor?.let { "Disconnected\u00A0$it" } ?: display.status
}

/**
 * "Charging to 80%" while charging, else the charge limit and the charging state, with the AC voltage, current
 * and speed between them: one quiet line under the hero.
 */
@Composable
private fun ChargeDetails(
    charge: ChargeState?,
    pricePerKwh: Float?,
    showSession: Boolean,
) {
    if (charge == null) return
    val charging = charge.chargingStateKind == ChargingStateKind.Charging
    val details =
        buildList {
            val limit = charge.charge_limit_soc
            when {
                charging -> add(limit?.let { "Charging to\u00A0$it%" } ?: chargingStateText(ChargingStateKind.Charging))
                limit != null -> add("Charge limit\u00A0$limit%")
            }
            chargerElectricalText(charge, LocalDistanceUnit.current)?.let { add(it) }
            if (!charging) charge.chargingStateKind?.let { add(chargingStateText(it)) }
            if (showSession) sessionEnergyText(charge.charge_energy_added, pricePerKwh, LocalCurrencyCode.current)?.let { add(it) }
        }
    if (details.isNotEmpty()) {
        Text(
            text = details.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "240V · 16/32A · 32mi/h": the charger's voltage, the actual current over the
 * requested limit, then the car's charging speed in rated distance per hour. Shown only while
 * charging, and each piece is dropped when the car reports none.
 */
private fun chargerElectricalText(
    charge: ChargeState,
    unit: DistanceUnit,
): String? {
    // Plugged in but idle, the car can still report the pilot's numbers; only a live charge is worth showing.
    if (charge.chargingStateKind != ChargingStateKind.Charging) return null
    val volts = charge.charger_voltage?.takeIf { it > 0 }
    val limit = (charge.charge_current_request ?: charge.charger_pilot_current)?.takeIf { it > 0 }
    val actual = charge.charger_actual_current
    val amps =
        when {
            actual != null && limit != null -> "$actual/${limit}A"
            actual != null && volts != null -> "${actual}A"
            limit != null -> "${limit}A"
            else -> null
        }
    val speed = charge.chargingMph?.let { "${unit.format(it)}/h" }
    return listOfNotNull(volts?.let { "${it}V" }, amps, speed).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * The big battery reading, colored like the cars list: primary while fresh,
 * outline once stale. With no reading at all, the connection state shows
 * instead. How old a stale reading is sits right of the "%".
 */
@Composable
private fun SocBlock(
    reading: BatteryPercent?,
    live: Boolean,
    rangeMiles: Float?,
    scale: FullRangeScale?,
    stateText: String,
    connection: TeslaConnection?,
    nowMillis: Long,
) {
    if (reading == null) {
        Text(text = stateText, style = MaterialTheme.typography.headlineSmall)
        return
    }
    val color = if (reading.stale) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
    // The fitted tenth is drawn small beside the "%", like the cents of a price.
    // One text node keeps one baseline and the digit order in right-to-left
    // locales; the age and range share the row's baseline.
    val tenths = scale?.socPercent(rangeMiles)?.let(::socTenths)
    val separator = remember { DecimalFormatSymbols.getInstance().decimalSeparator }
    val percentStyle = MaterialTheme.typography.headlineSmall.toSpanStyle()
    val socText =
        buildAnnotatedString {
            append("${tenths?.div(TENTHS_PER_PERCENT) ?: reading.value}")
            withStyle(percentStyle) { append(tenths?.let { "$separator${it % TENTHS_PER_PERCENT}%" } ?: "%") }
        }
    val spoken = tenths?.let { "${it / TENTHS_PER_PERCENT}$separator${it % TENTHS_PER_PERCENT}" } ?: "${reading.value}"
    val description = stringResource(R.string.soc_description, spoken)
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = socText,
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.alignByBaseline().semantics { contentDescription = description },
        )
        reading.ageLabel(nowMillis, connection)?.let { age ->
            Text(
                text = age,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alignByBaseline().padding(start = 6.dp),
            )
        }
        // The range hugs the bottom-right, just above the progress bar, and wraps
        // right-aligned instead of crowding the age on narrow screens.
        rangeMiles?.let { miles ->
            Text(
                text = "Range ${formatDistance(miles)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).alignByBaseline().padding(start = 8.dp),
            )
        }
    }
    if (live) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { reading.value / 100f },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            color = color,
        )
    }
}

/** True when the app key is enrolled (or just was): the car can authenticate. */
private fun isPaired(
    connection: TeslaConnection?,
    vehicle: Vehicle?,
): Boolean = connection?.isPaired == true || vehicle?.isPaired == true

@Composable
private fun KeyCard(
    connection: TeslaConnection?,
    vehicle: Vehicle?,
    onPair: () -> Unit,
) {
    // The stored slot proves enrollment even before the car answers.
    val keySlot = connection?.keySlot ?: vehicle?.keySlot
    val paired = isPaired(connection, vehicle)
    val pairing = connection?.pairing ?: PairingPhase.IDLE
    val pairingInProgress =
        pairing == PairingPhase.CHECKING ||
            pairing == PairingPhase.SENDING ||
            pairing == PairingPhase.WAITING_FOR_CARD
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("App key", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text =
                            when {
                                !paired -> "Not paired"
                                keySlot != null -> "Paired · slot\u00A0$keySlot"
                                else -> "Paired"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!paired) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text =
                                "Pair so this phone can wake and read the car; keep an " +
                                    "NFC card ready for the car's prompt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!paired) {
                    Button(
                        onClick = onPair,
                        enabled = connection?.phase == ConnectionPhase.READY && !pairingInProgress,
                    ) {
                        Text("Pair key")
                    }
                }
            }
            if (pairing != PairingPhase.IDLE) {
                Spacer(Modifier.height(8.dp))
                PairingStatus(pairing = pairing, keyId = connection?.pairingKeyId)
            }
        }
    }
}

@Composable
private fun PairingStatus(
    pairing: PairingPhase,
    keyId: String?,
) {
    val inProgress =
        pairing == PairingPhase.CHECKING ||
            pairing == PairingPhase.SENDING ||
            pairing == PairingPhase.WAITING_FOR_CARD
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (inProgress) {
            BusySpinner(size = 16.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column {
            Text(
                text = pairingStatusText(pairing, keyId),
                style = MaterialTheme.typography.bodySmall,
                color =
                    when (pairing) {
                        PairingPhase.OK -> MaterialTheme.colorScheme.primary
                        PairingPhase.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            pairingDetailText(pairing)?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun pairingStatusText(
    pairing: PairingPhase,
    keyId: String?,
): String =
    when (pairing) {
        PairingPhase.CHECKING -> "Checking whether this phone is already paired…"
        PairingPhase.SENDING -> "Pairing request sent — waiting for the car."
        PairingPhase.WAITING_FOR_CARD ->
            "Tap your NFC card on the center console and confirm on the car screen."

        PairingPhase.OK -> "Key paired: $keyId"
        PairingPhase.ERROR ->
            "Pairing not confirmed. Hold the NFC card on the center console and try again."

        PairingPhase.IDLE -> ""
    }

private fun pairingDetailText(pairing: PairingPhase): String? =
    when (pairing) {
        PairingPhase.SENDING, PairingPhase.WAITING_FOR_CARD -> "Waiting for the car to confirm the key..."
        PairingPhase.OK -> "Rename the Phone Key in Controls > Locks on the Tesla screen."
        else -> null
    }

// ---------------------------------------------------------------- battery card

@Composable
private fun ChargeProjectionLine(
    charge: ChargeProjection,
    is24Hour: Boolean,
) {
    Spacer(Modifier.height(6.dp))
    Text(
        text =
            "Charging · projected ${charge.targetPercent}% around " +
                formatTime(charge.completionMillis, showDate = true, is24Hour = is24Hour) +
                charge.beam
                    ?.let { (earliest, latest) ->
                        " (${formatTime(
                            earliest,
                            showDate = false,
                            is24Hour = is24Hour,
                        )}–${formatTime(latest, showDate = false, is24Hour = is24Hour)})"
                    }.orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        color = chargingChartColor,
    )
}

@Composable
internal fun BatteryHistoryCard(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    // Injectable for the Paparazzi snapshots; production call sites use the
    // default, which is the same wall-clock read as before.
    nowMillis: Long = System.currentTimeMillis(),
    // Injectable for the Paparazzi snapshots; production opens on the day range.
    initialRange: HistoryRange = HistoryRange.DAY,
    // The status log and the Sentry and climate readings: when the car sat locked and alone, for the sleep drain rate.
    sleepLogs: ProjectionContext = ProjectionContext(),
) {
    var range by rememberSaveable { mutableStateOf(initialRange) }
    val is24Hour = LocalIs24Hour.current ?: DateFormat.is24HourFormat(LocalContext.current)
    val now = nowMillis
    // Fitted once: the stats, the projection and the chart all read it.
    val scale = remember(samples) { FullRangeScales.of(samples) }
    val withScale = remember(sleepLogs, scale) { sleepLogs.copy(scale = scale) }
    val priceChanges = LocalPriceChanges.current
    // Every figure scans the whole log, so they run off the main thread; nothing below shows until the first result.
    val figures =
        rememberInBackground(null, samples, drives, range, now, withScale) {
            batteryHistoryFigures(samples, drives, range, now, withScale)
        } ?: return
    val visible = figures.visible
    val stats = figures.lastCharge
    val lastChargePrice =
        rememberInBackground(null, stats, samples, priceChanges) { stats?.let { priceForCharge(priceChanges, samples, it.sinceMillis) } }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Battery history", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            // Five chips overflow a narrow phone; they scroll sideways rather than squeeze.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryRange.entries.forEach { entry ->
                    FilterChip(
                        selected = range == entry,
                        onClick = { range = entry },
                        label = { Text(entry.label()) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (visible.isEmpty()) {
                Text(
                    text = "No samples yet. SOC is recorded while the car is awake.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val baseMillis = figures.baseMillis
                val charge = figures.charge
                val discharge = figures.discharge
                // The projection gets room on the x-axis: its round-figure horizon ahead.
                val windowEnd =
                    if (charge != null || discharge != null) {
                        now + projectionHorizonMillis(baseMillis)
                    } else {
                        now
                    }
                BatteryChart(
                    data = ChartData(visible, charge, discharge, scale),
                    axis =
                        TimeAxis(
                            windowStart = range.durationMillis?.let { now - it } ?: visible.first().timestampMillis,
                            windowEnd = windowEnd,
                            showDate = range != HistoryRange.SIX_HOURS,
                            is24Hour = is24Hour,
                        ),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                )
                charge?.let { ChargeProjectionLine(it, is24Hour) }
                discharge?.let {
                    Spacer(Modifier.height(6.dp))
                    ParkedProjectionLine(
                        projection = it,
                        // The chart's measured line colour, as the charging line matches its green.
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            stats?.let {
                Spacer(Modifier.height(8.dp))
                val energy = sessionEnergyText(it.energyKwh, lastChargePrice, LocalCurrencyCode.current)
                Text(
                    text =
                        "Last charge:\u00A0${formatPercent(
                            it.chargePercent(scale),
                        )}% · ${agoText(now - it.sinceMillis)}" +
                            energy?.let { e -> " · $e" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** What the battery history card shows, derived from the whole log for one range and moment. */
private class BatteryHistoryFigures(
    val visible: List<BatterySample>,
    /** The visible span the projections are scaled to: the range, or the samples' own span for All. */
    val baseMillis: Long,
    val charge: ChargeProjection?,
    val discharge: DischargeProjection?,
    val lastCharge: ChargeStats?,
)

private fun batteryHistoryFigures(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    range: HistoryRange,
    nowMillis: Long,
    context: ProjectionContext,
): BatteryHistoryFigures {
    val visible = samples.within(range, nowMillis)
    val baseMillis =
        range.durationMillis
            ?: ((visible.lastOrNull()?.timestampMillis ?: 0L) - (visible.firstOrNull()?.timestampMillis ?: 0L)).coerceAtLeast(1L)
    // Projections are derived from every sample, not just the visible window, so a run that started before the
    // selected range still projects. Parked drain: the drive samples' odometer marks the stretches the car moved;
    // shown next to the charge projection too, where the battery heads if unplugged now.
    val projections = visible.isNotEmpty()
    return BatteryHistoryFigures(
        visible = visible,
        baseMillis = baseMillis,
        charge = if (projections) chargeProjection(samples, scale = context.scale) else null,
        discharge = if (projections) dischargeProjection(samples, drives, baseMillis, nowMillis, context) else null,
        lastCharge = lastFinishedChargeStats(samples, nowMillis),
    )
}

@Composable
private fun HistoryRange.label(): String =
    stringResource(
        when (this) {
            HistoryRange.SIX_HOURS -> R.string.history_range_6h
            HistoryRange.DAY -> R.string.history_range_24h
            HistoryRange.WEEK -> R.string.history_range_7d
            HistoryRange.MONTH -> R.string.history_range_30d
            HistoryRange.ALL -> R.string.history_range_all
        },
    )

private const val REFRESH_FEEDBACK_MS = 2500L

private val EDIT_ICON_LIFT = (-10).dp
private val EDIT_ICON = 18.dp

/** Green shared by charging segments, dots, and the projected line. */
internal val chargingChartColor = Color(0xFF43A047)

/**
 * Overrides the phone's 12/24-hour switch for the chart times; null follows it.
 * The Paparazzi snapshots pin it so renders do not depend on the machine.
 */
internal val LocalIs24Hour = staticCompositionLocalOf<Boolean?> { null }

private val timeFormatters = ConcurrentHashMap<Triple<Locale, Boolean, Boolean>, DateTimeFormatter>()

// The skeleton carries the phone's 12/24-hour choice (H or h); the locale still
// decides the rest, so US reads "Oct 18, 9:05 PM" or "Oct 18, 21:05".
private fun timeFormatter(
    showDate: Boolean,
    is24Hour: Boolean,
): DateTimeFormatter {
    val locale = Locale.getDefault()
    return timeFormatters.getOrPut(Triple(locale, showDate, is24Hour)) {
        val hour = if (is24Hour) "H" else "h"
        val skeleton = (if (showDate) "MMMd" else "") + hour + "m"
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }
}

internal fun formatTime(
    millis: Long,
    showDate: Boolean,
    is24Hour: Boolean,
): String =
    Instant
        .ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(timeFormatter(showDate, is24Hour))

/** "12 min ago" from an age in millis, "<1min ago" below one. */
@Composable
private fun agoText(millis: Long): String =
    stringResource(R.string.duration_ago, compactDuration(millis.coerceAtLeast(0L)) ?: stringResource(R.string.trip_under_a_minute))

@Composable
private fun formatDistance(miles: Float): String {
    val separator = remember { DecimalFormatSymbols.getInstance().decimalSeparator }
    return LocalDistanceUnit.current.format(miles, decimals = 1, decimalSeparator = separator)
}

/** One decimal only when it adds information: "78%" but "77.6%". */
internal fun formatPercent(value: Float): String {
    val rounded = value.roundToInt()
    return if (rounded.toFloat() == value) "$rounded" else String.format(Locale.getDefault(), "%.1f", value)
}
