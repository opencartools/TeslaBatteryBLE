// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.annotation.StringRes
import com.dzid26.teslable.R
import com.dzid26.teslable.ble.ActuationKey
import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.chargingStateKind
import com.tesla.generated.carserver.vehicle.ChargeState

/** The step Tesla's app uses. The car accepts any percent target. */
internal const val CHARGE_LIMIT_STEP = 5

/**
 * The smallest current this stepper offers. Tesla's app stops at 5 A; the car decides whether
 * it accepts a lower request, so this build lets the owner try.
 */
internal const val MIN_CHARGING_AMPS = 1

/** How long an optimistic value stays up before the car's own value takes it back. */
internal const val APPLY_TIMEOUT_MS = 15_000L

/**
 * Tesla's charge-limit range. The car narrows it with `charge_limit_soc_min` and
 * `charge_limit_soc_max`; these values are the API's bounds.
 */
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

/** The car's settable limit. Its reported min and max are the source; the API bounds only clamp them. */
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
): Int {
    val next =
        if (delta > 0) {
            (current / CHARGE_LIMIT_STEP + 1) * CHARGE_LIMIT_STEP
        } else {
            ((current - 1) / CHARGE_LIMIT_STEP) * CHARGE_LIMIT_STEP
        }
    return next.coerceIn(range.first, range.last)
}

/** One amp steps, never below the smallest request the API accepts. */
internal fun nextChargingAmps(
    current: Int,
    delta: Int,
    max: Int,
): Int = (current + delta).coerceIn(minOf(MIN_CHARGING_AMPS, max), max)

/**
 * One stepper's content and actions, independent of how the row is laid out. The row supplies the
 * optimistic value and the layout; the model says what the control is, how it steps and who sends it.
 */
internal data class ChargeStepperModel(
    val key: ActuationKey,
    @StringRes val label: Int,
    @StringRes val valueFormat: Int,
    val current: Int,
    val range: IntRange,
    val enabled: Boolean,
    @StringRes val decreaseLabel: Int,
    @StringRes val increaseLabel: Int,
    /** (value, +1/-1) to the next value on the control's own grid. */
    val step: (Int, Int) -> Int,
    val set: (Int) -> Unit,
)

/** Everything the charge card shows for one car state, or null when there is nothing to show. */
internal data class ChargeControlsModel(
    val limit: ChargeStepperModel?,
    val amps: ChargeStepperModel?,
    val button: ChargeButton,
)

/**
 * Derives the card's content from the car's last charge state. A DC fast charger keeps both rows but
 * disables the current one; an unplugged car still offers the limit so it can be set before plugging in.
 * An open charge port shows the card with a Start button even when the car has not reported a state,
 * because the port is the only signal available before the next Infotainment read.
 */
internal fun chargeControlsModel(
    charge: ChargeState?,
    actions: ChargeControlActions,
    portOpen: Boolean = false,
): ChargeControlsModel? {
    if (charge == null) {
        return if (portOpen) ChargeControlsModel(limit = null, amps = null, button = ChargeButton.START) else null
    }
    val button =
        if (portOpen && chargeButton(charge) == ChargeButton.NONE) {
            ChargeButton.START
        } else {
            chargeButton(charge)
        }
    val limit =
        charge.charge_limit_soc?.let { current ->
            val range = chargeLimitRange(charge)
            ChargeStepperModel(
                key = ActuationKey.CHARGE_LIMIT,
                label = R.string.charge_controls_limit,
                valueFormat = R.string.charge_controls_percent,
                current = current,
                range = range,
                enabled = true,
                decreaseLabel = R.string.charge_controls_limit_decrease,
                increaseLabel = R.string.charge_controls_limit_increase,
                step = { value, delta -> nextChargeLimit(value, delta, range) },
                set = actions.setLimit,
            )
        }
    val amps =
        charge.charge_current_request?.takeIf { it > 0 }?.let { current ->
            val max = maxOf(current, charge.charge_current_request_max ?: current)
            ChargeStepperModel(
                key = ActuationKey.CHARGING_AMPS,
                label = R.string.charge_controls_current,
                valueFormat = R.string.charge_controls_amps,
                current = current,
                range = minOf(MIN_CHARGING_AMPS, max)..max,
                enabled = charge.fast_charger_present != true,
                decreaseLabel = R.string.charge_controls_current_decrease,
                increaseLabel = R.string.charge_controls_current_increase,
                step = { value, delta -> nextChargingAmps(value, delta, max) },
                set = actions.setAmps,
            )
        }
    return if (limit == null && amps == null && button == ChargeButton.NONE) {
        null
    } else {
        ChargeControlsModel(limit, amps, button)
    }
}

/** The card's callbacks, grouped so the composable stays within the parameter limit. */
internal data class ChargeControlActions(
    val setLimit: (Int) -> Unit,
    val setAmps: (Int) -> Unit,
    val start: () -> Unit,
    val stop: () -> Unit,
)
