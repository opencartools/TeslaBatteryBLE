// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.chargingStateKind
import com.tesla.generated.carserver.vehicle.ChargeState

/** The charge limit moves in 5% steps, as the car's own app does. */
internal const val CHARGE_LIMIT_STEP = 5

/** The lowest current a car accepts; a packed car can still report and use less. */
internal const val MIN_CHARGING_AMPS = 5

/** How long an optimistic value stays up before the car's own value takes it back. */
internal const val APPLY_TIMEOUT_MS = 15_000L

private const val CHARGE_LIMIT_MIN = 50
private const val CHARGE_LIMIT_MAX = 100

/** What the start/stop button offers for the car's current charging state. */
internal enum class ChargeButton { START, STOP, NONE }

internal fun chargeButton(charge: ChargeState): ChargeButton =
    when (charge.chargingStateKind) {
        ChargingStateKind.Charging, ChargingStateKind.Starting, ChargingStateKind.Calibrating -> ChargeButton.STOP
        ChargingStateKind.Stopped -> ChargeButton.START
        else -> ChargeButton.NONE
    }

/** The car's settable limit, clamped to what the car and the app agree on. */
internal fun chargeLimitRange(charge: ChargeState): IntRange {
    val min =
        charge.charge_limit_soc_min
            ?.coerceIn(CHARGE_LIMIT_MIN, CHARGE_LIMIT_MAX)
            ?: CHARGE_LIMIT_MIN
    val max =
        charge.charge_limit_soc_max
            ?.coerceIn(min, CHARGE_LIMIT_MAX)
            ?: CHARGE_LIMIT_MAX
    return min..max
}

internal fun nextChargeLimit(
    current: Int,
    delta: Int,
    range: IntRange,
): Int = (current + delta * CHARGE_LIMIT_STEP).coerceIn(range.first, range.last)

internal fun nextChargingAmps(
    current: Int,
    delta: Int,
    max: Int,
): Int = (current + delta).coerceIn(minOf(MIN_CHARGING_AMPS, max), max)

/**
 * Whether the charge controls have anything to show: nothing until the car has reported a limit, a
 * current or a charging state. A DC fast charger still shows the card, with the current control
 * disabled; an unplugged car still shows them, so the limit can be set before a cable is attached.
 */
internal fun showsChargeControls(charge: ChargeState?): Boolean =
    charge != null &&
        (charge.charge_limit_soc != null || (charge.charge_current_request ?: 0) > 0 || chargeButton(charge) != ChargeButton.NONE)

/** The card's callbacks, grouped so the composable stays within the parameter limit. */
internal data class ChargeControlActions(
    val setLimit: (Int) -> Unit,
    val setAmps: (Int) -> Unit,
    val start: () -> Unit,
    val stop: () -> Unit,
    val clearFailure: () -> Unit,
)
