// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.dzid26.teslable.ble.ActuationFeedback
import com.dzid26.teslable.ble.ActuationKey
import com.dzid26.teslable.ble.ActuationResult
import com.tesla.generated.carserver.vehicle.ChargeState
import kotlinx.coroutines.delay

/** How long a quiet refusal stays on the card before it goes on its own. */
private const val QUIET_FAILURE_MS = 6_000L

/** One car's charge-control readings, grouped so the section stays within the parameter limit. */
internal data class ChargeControlsState(
    /** The car the readings belong to: the section keeps its feedback per car. */
    val carKey: Any?,
    val model: ChargeControlsModel?,
    val charge: ChargeState?,
    val actuating: Set<ActuationKey>,
    val feedback: ActuationFeedback?,
)

/**
 * The charge controls' section: the card and the feedback it produces. Start and stop report in the
 * screen's snackbar; a refused limit or current paints an inline line that the car's next reading, a
 * success, or [QUIET_FAILURE_MS] clears. Every reading here comes from the car's own connection, so a
 * background car cannot paint the open screen.
 *
 * The section stays composed even while [ChargeControlsState.model] is null, so a result that arrives
 * with the card hidden is taken and shown once rather than replayed on the next visit.
 */
@Composable
internal fun ChargeControlsSection(
    state: ChargeControlsState,
    snackbarHostState: SnackbarHostState,
    onFeedbackShown: () -> Unit,
    actions: ChargeControlActions,
) {
    val feedbackText = state.feedback?.let { stringResource(it.message()) }
    var quietFailure by remember(state.carKey) { mutableStateOf<ActuationKey?>(null) }
    var snackbarText by remember(state.carKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.feedback) {
        val event = state.feedback ?: return@LaunchedEffect
        // Take the event before showing it: a screen left mid-snackbar must not replay it next visit.
        if (event.key.failureMessage() == null) {
            snackbarText = feedbackText
        } else {
            // A lost answer is not a refusal: the value or the next read decides, so leave the line alone.
            // A success clears a line left by the previous tap; only a refusal or a failure paints one.
            when (event.result) {
                ActuationResult.REFUSED, ActuationResult.FAILED -> quietFailure = event.key
                ActuationResult.ACCEPTED -> quietFailure = null
                ActuationResult.NO_ANSWER -> Unit
            }
        }
        onFeedbackShown()
    }
    LaunchedEffect(snackbarText) {
        val text = snackbarText ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        snackbarText = null
    }
    // A tap clears the previous line as soon as its action is queued; the card never mutates this state.
    LaunchedEffect(state.actuating) {
        if (state.actuating.isNotEmpty()) quietFailure = null
    }
    // A refusal stays while the owner reads it, then goes; a stale line must never outlive its action.
    LaunchedEffect(quietFailure) {
        if (quietFailure == null) return@LaunchedEffect
        delay(QUIET_FAILURE_MS)
        quietFailure = null
    }
    // The card went away (unpaired, no VIN, link down): its line goes with it.
    LaunchedEffect(state.model == null) {
        if (state.model == null) quietFailure = null
    }
    // The car reported a value for the control the line was about: it has been settled.
    LaunchedEffect(state.charge?.charge_limit_soc) {
        if (quietFailure == ActuationKey.CHARGE_LIMIT) quietFailure = null
    }
    LaunchedEffect(state.charge?.charge_current_request) {
        if (quietFailure == ActuationKey.CHARGING_AMPS) quietFailure = null
    }
    val model = state.model
    if (model != null) {
        ChargeControlsCard(
            model = model,
            actuating = state.actuating,
            failure = quietFailure,
            actions = actions,
        )
    }
}
