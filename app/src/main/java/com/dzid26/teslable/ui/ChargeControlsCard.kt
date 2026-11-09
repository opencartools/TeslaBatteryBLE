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
import kotlinx.coroutines.delay

/** The charge limit, the charging current and start/stop, initialized from the car's own values. */
@Composable
internal fun ChargeControlsCard(
    model: ChargeControlsModel,
    actuating: Set<ActuationKey>,
    failure: ActuationKey?,
    actions: ChargeControlActions,
    modifier: Modifier = Modifier,
) {
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
            model.limit?.let { ChargeStepperRow(it, failure, actions) }
            model.amps?.let { ChargeStepperRow(it, failure, actions) }
            ChargeButtonRow(model.button, actuating, actions)
            ChargeFailureLine(failure)
        }
    }
}

/** One stepper; its value is the car's until the owner changes it, then the value being sent. */
@Composable
private fun ChargeStepperRow(
    model: ChargeStepperModel,
    failure: ActuationKey?,
    actions: ChargeControlActions,
) {
    var draft by remember(model.current) { mutableStateOf<Int?>(null) }
    LaunchedEffect(failure) {
        if (failure == model.key) draft = null
    }
    LaunchedEffect(draft) {
        val target = draft ?: return@LaunchedEffect
        delay(APPLY_TIMEOUT_MS)
        if (draft == target) draft = null
    }
    val shown = draft ?: model.current
    val decreaseLabel = stringResource(model.decreaseLabel)
    val increaseLabel = stringResource(model.increaseLabel)

    fun apply(next: Int) {
        actions.clearFailure()
        draft = next
        model.set(next)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(model.label),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        IconButton(
            onClick = { apply(model.step(shown, -1)) },
            enabled = model.enabled && shown > model.range.first,
        ) {
            Text(
                text = "\u2212",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.clearAndSetSemantics { contentDescription = decreaseLabel },
            )
        }
        Box(
            modifier = Modifier.widthIn(min = 64.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(model.valueFormat, shown),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        IconButton(
            onClick = { apply(model.step(shown, 1)) },
            enabled = model.enabled && shown < model.range.last,
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.clearAndSetSemantics { contentDescription = increaseLabel },
            )
        }
    }
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
