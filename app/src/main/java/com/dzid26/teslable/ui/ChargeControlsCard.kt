// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.R
import com.dzid26.teslable.ble.ActuationFeedback
import com.dzid26.teslable.ble.ActuationKey
import com.dzid26.teslable.ble.ActuationResult
import com.tesla.generated.carserver.vehicle.ChargeState
import kotlinx.coroutines.delay

/** The charge limit, the charging current and start/stop, initialized from the car's own values. */
@Composable
internal fun ChargeControlsCard(
    charge: ChargeState,
    actuating: Set<ActuationKey>,
    failure: ActuationKey?,
    actions: ChargeControlActions,
    modifier: Modifier = Modifier,
) {
    val limit = charge.charge_limit_soc
    val amps = charge.charge_current_request?.takeIf { it > 0 }
    val button = chargeButton(charge)
    if (limit == null && amps == null && button == ChargeButton.NONE) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.charge_controls_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                // One card-level busy state: any queued or in-flight control turns this.
                if (actuating.isNotEmpty()) {
                    BusySpinner(size = 16.dp)
                }
            }
            ChargeLimitRow(charge, failure, actions)
            ChargeCurrentRow(charge, failure, actions)
            ChargeButtonRow(button, actuating, actions)
            ChargeFailureLine(failure)
        }
    }
}

/** The limit stepper; its value is the car's until the owner changes it, then the value being sent. */
@Composable
private fun ChargeLimitRow(
    charge: ChargeState,
    failure: ActuationKey?,
    actions: ChargeControlActions,
) {
    val current = charge.charge_limit_soc ?: return
    var draft by remember(charge.charge_limit_soc) { mutableStateOf<Int?>(null) }
    LaunchedEffect(failure) {
        if (failure == ActuationKey.CHARGE_LIMIT) draft = null
    }
    LaunchedEffect(draft) {
        val target = draft ?: return@LaunchedEffect
        delay(APPLY_TIMEOUT_MS)
        if (draft == target) draft = null
    }
    val range = chargeLimitRange(charge)
    val shown = draft ?: current
    StepperRow(
        Stepper(
            label = stringResource(R.string.charge_controls_limit),
            value = stringResource(R.string.charge_controls_percent, shown),
            canDecrease = shown > range.first,
            canIncrease = shown < range.last,
            decreaseLabel = stringResource(R.string.charge_controls_limit_decrease),
            increaseLabel = stringResource(R.string.charge_controls_limit_increase),
            onDecrease = {
                actions.clearFailure()
                val next = nextChargeLimit(shown, -1, range)
                draft = next
                actions.setLimit(next)
            },
            onIncrease = {
                actions.clearFailure()
                val next = nextChargeLimit(shown, 1, range)
                draft = next
                actions.setLimit(next)
            },
        ),
    )
}

/** The charging-current stepper, shown while the car reports a current. */
@Composable
private fun ChargeCurrentRow(
    charge: ChargeState,
    failure: ActuationKey?,
    actions: ChargeControlActions,
) {
    val current = charge.charge_current_request?.takeIf { it > 0 } ?: return
    var draft by remember(charge.charge_current_request) { mutableStateOf<Int?>(null) }
    LaunchedEffect(failure) {
        if (failure == ActuationKey.CHARGING_AMPS) draft = null
    }
    LaunchedEffect(draft) {
        val target = draft ?: return@LaunchedEffect
        delay(APPLY_TIMEOUT_MS)
        if (draft == target) draft = null
    }
    val max = maxOf(current, charge.charge_current_request_max ?: current)
    val shown = draft ?: current
    val enabled = charge.fast_charger_present != true
    StepperRow(
        Stepper(
            label = stringResource(R.string.charge_controls_current),
            value = stringResource(R.string.charge_controls_amps, shown),
            canDecrease = enabled && shown > minOf(MIN_CHARGING_AMPS, max),
            canIncrease = enabled && shown < max,
            decreaseLabel = stringResource(R.string.charge_controls_current_decrease),
            increaseLabel = stringResource(R.string.charge_controls_current_increase),
            onDecrease = {
                actions.clearFailure()
                val next = nextChargingAmps(shown, -1, max)
                draft = next
                actions.setAmps(next)
            },
            onIncrease = {
                actions.clearFailure()
                val next = nextChargingAmps(shown, 1, max)
                draft = next
                actions.setAmps(next)
            },
        ),
    )
}

@Composable
private fun ChargeButtonRow(
    button: ChargeButton,
    actuating: Set<ActuationKey>,
    actions: ChargeControlActions,
) {
    when (button) {
        ChargeButton.START ->
            ControlButton(
                start = true,
                pending = ActuationKey.CHARGE_START in actuating,
                onClick = {
                    actions.clearFailure()
                    actions.start()
                },
            )

        ChargeButton.STOP ->
            ControlButton(
                start = false,
                pending = ActuationKey.CHARGE_STOP in actuating,
                onClick = {
                    actions.clearFailure()
                    actions.stop()
                },
            )

        ChargeButton.NONE -> Unit
    }
}

/** The inline message for a quiet control the car refused; the loud controls report in the snackbar. */
@Composable
private fun ChargeFailureLine(failure: ActuationKey?) {
    val message = failure?.failureMessage() ?: return
    Text(
        text = stringResource(message),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** One stepper's labels, bounds and callbacks. */
private data class Stepper(
    val label: String,
    val value: String,
    val canDecrease: Boolean,
    val canIncrease: Boolean,
    val decreaseLabel: String,
    val increaseLabel: String,
    val onDecrease: () -> Unit,
    val onIncrease: () -> Unit,
)

@Composable
private fun StepperRow(step: Stepper) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = step.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        IconButton(onClick = step.onDecrease, enabled = step.canDecrease) {
            Text(
                text = "\u2212",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.clearAndSetSemantics { contentDescription = step.decreaseLabel },
            )
        }
        Box(
            modifier = Modifier.widthIn(min = 64.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = step.value,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = step.onIncrease, enabled = step.canIncrease) {
            Text(
                text = "+",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.clearAndSetSemantics { contentDescription = step.increaseLabel },
            )
        }
    }
}

@Composable
private fun ControlButton(
    start: Boolean,
    pending: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = !pending,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (start) R.string.charge_controls_start else R.string.charge_controls_stop))
    }
}

/** The inline message for a quiet control the car refused; null for the two that use the snackbar. */
@StringRes
internal fun ActuationKey.failureMessage(): Int? =
    when (this) {
        ActuationKey.CHARGE_LIMIT -> R.string.charge_controls_limit_failed
        ActuationKey.CHARGING_AMPS -> R.string.charge_controls_current_failed
        ActuationKey.CHARGE_START, ActuationKey.CHARGE_STOP -> null
    }

/** The snackbar text for the loud controls; the quiet ones are never reported this way. */
@StringRes
internal fun ActuationFeedback.message(): Int =
    when (key) {
        ActuationKey.CHARGE_START ->
            when (result) {
                ActuationResult.ACCEPTED -> R.string.charge_controls_started
                ActuationResult.NO_ANSWER -> R.string.charge_controls_no_answer
                ActuationResult.REFUSED, ActuationResult.FAILED -> R.string.charge_controls_start_failed
            }

        ActuationKey.CHARGE_STOP ->
            when (result) {
                ActuationResult.ACCEPTED -> R.string.charge_controls_stopped
                ActuationResult.NO_ANSWER -> R.string.charge_controls_no_answer
                ActuationResult.REFUSED, ActuationResult.FAILED -> R.string.charge_controls_stop_failed
            }

        ActuationKey.CHARGE_LIMIT -> R.string.charge_controls_limit_failed
        ActuationKey.CHARGING_AMPS -> R.string.charge_controls_current_failed
    }
