// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.annotation.StringRes
import com.dzid26.teslable.R
import com.dzid26.teslable.ble.ActuationKey
import com.dzid26.teslable.ble.CommandRecorder
import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.chargingStateKind
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.vcsec.ClosureState_E

/** The step Tesla's app uses. The car accepts any percent target. */
internal const val CHARGE_LIMIT_STEP = 5

/**
 * The smallest current this stepper offers. Tesla's app stops at 5 A; the car decides whether
 * it accepts a lower request, so this build lets the owner try.
 */
internal const val MIN_CHARGING_AMPS = 1

/**
 * How long an optimistic value stays up. It follows the command log's unanswered wait: by then the
 * app has either seen the reply or given up on it, so the car's own value is the truth again.
 */
internal const val APPLY_TIMEOUT_MS = CommandRecorder.TIMEOUT_MS

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
    /** The car's value; null until the first reading, which keeps the row in place while it is read. */
    val current: Int?,
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
    /** False while the link is not ready: the card keeps the held reading but the controls do nothing. */
    val enabled: Boolean,
    /** True while an open port waits for its first charge reading: the card says it is reading. */
    val reading: Boolean = false,
)

/** Whether the car reports its charge port open. VCSEC's closure and the charge state's own flag both say so. */
internal fun isPortOpen(
    closure: ClosureState_E?,
    doorOpen: Boolean?,
): Boolean = closure == ClosureState_E.CLOSURESTATE_OPEN || doorOpen == true

/**
 * The card's content for one car state, or null when the charge port is closed or there is nothing
 * to show. The rows and button follow the last charge state; [enabled] says whether the link can
 * send, and [reading] marks a card waiting for its first state.
 */
internal fun chargeControlsModel(
    charge: ChargeState?,
    actions: ChargeControlActions,
    portOpen: Boolean,
    enabled: Boolean,
    asleep: Boolean,
): ChargeControlsModel? =
    if (!portOpen) {
        null
    } else if (charge == null) {
        // Keep both rows in place while the first reading is on its way.
        ChargeControlsModel(
            limit = unknownChargeLimit(),
            amps = unknownChargingAmps(),
            button = if (asleep) ChargeButton.START else ChargeButton.NONE,
            enabled = enabled,
            reading = !asleep,
        )
    } else {
        // A sleeping car's open port is taken as stopped; an awake one waits for its reading.
        val button = if (asleep) ChargeButton.START else chargeButton(charge)
        val limit =
            charge.charge_limit_soc?.let { current ->
                val range = chargeLimitRange(charge)
                ChargeStepperModel(
                    key = ActuationKey.CHARGE_LIMIT,
                    label = R.string.charge_controls_limit,
                    valueFormat = R.string.charge_controls_percent,
                    current = current,
                    range = range,
                    enabled = enabled,
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
                    enabled = enabled && charge.fast_charger_present != true,
                    decreaseLabel = R.string.charge_controls_current_decrease,
                    increaseLabel = R.string.charge_controls_current_increase,
                    step = { value, delta -> nextChargingAmps(value, delta, max) },
                    set = actions.setAmps,
                )
            }
        val hasContent = limit != null || amps != null || button != ChargeButton.NONE
        if (hasContent) ChargeControlsModel(limit, amps, button, enabled) else null
    }

/** The limit row before the first reading: same shape, no value and no action. */
private fun unknownChargeLimit() =
    ChargeStepperModel(
        key = ActuationKey.CHARGE_LIMIT,
        label = R.string.charge_controls_limit,
        valueFormat = R.string.charge_controls_percent,
        current = null,
        range = IntRange.EMPTY,
        enabled = false,
        decreaseLabel = R.string.charge_controls_limit_decrease,
        increaseLabel = R.string.charge_controls_limit_increase,
        step = { value, _ -> value },
        set = {},
    )

/** The current row before the first reading: same shape, no value and no action. */
private fun unknownChargingAmps() =
    ChargeStepperModel(
        key = ActuationKey.CHARGING_AMPS,
        label = R.string.charge_controls_current,
        valueFormat = R.string.charge_controls_amps,
        current = null,
        range = IntRange.EMPTY,
        enabled = false,
        decreaseLabel = R.string.charge_controls_current_decrease,
        increaseLabel = R.string.charge_controls_current_increase,
        step = { value, _ -> value },
        set = {},
    )

/** The card's callbacks, grouped so the composable stays within the parameter limit. */
internal data class ChargeControlActions(
    val setLimit: (Int) -> Unit,
    val setAmps: (Int) -> Unit,
    val start: () -> Unit,
    val stop: () -> Unit,
)
