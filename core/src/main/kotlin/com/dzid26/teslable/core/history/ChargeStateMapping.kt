// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.protocol.chargingMph
import com.dzid26.teslable.protocol.chargingStateKind
import com.tesla.generated.carserver.vehicle.ChargeState

// One-way conversion: raw car ChargeState records kept in the history log
// become the app's BatterySample read model. The read model is derived on
// load; nothing ever writes a record back from parsed fields — the store logs
// the car's raw response verbatim. The charging state comes from the
// shared property in protocol/StateViews.kt, never re-derived here.

/** The app-facing sample for a raw record; null when the car sent no level or no timestamp. */
fun ChargeState.toBatterySample(vehicleId: String): BatterySample? {
    val level = battery_level ?: return null
    val time = timestamp?.toEpochMilli() ?: return null
    return BatterySample(
        timestampMillis = time,
        batteryLevel = level,
        chargingState = chargingStateKind,
        chargeLimit = charge_limit_soc,
        vehicleId = vehicleId,
        ratedRangeMiles = battery_range,
        chargeEnergyAdded = charge_energy_added,
        chargeMilesAddedRated = charge_miles_added_rated,
        chargerVoltage = charger_voltage,
        chargeRateMph = chargingMph,
        fastChargerPresent = fast_charger_present,
        usableLevel = usable_battery_level ?: level,
    )
}
