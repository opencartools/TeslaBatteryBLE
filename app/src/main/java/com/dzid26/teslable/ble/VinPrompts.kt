// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the VIN prompt in [state]: which car asked for one, and why. The controller routes user actions
 * through [request] and [saved], so the prompt's small state machine lives in one place and every caller
 * treats a missing VIN the same. Only user intent goes through here: background reads skip silently.
 *
 * A link dropping or reconnecting never clears the prompt: it stays until the owner saves, cancels,
 * switches cars, or turns tracking off, so a transient BLE drop cannot dismiss the dialog mid-sentence.
 */
internal class VinPrompts(
    private val state: MutableStateFlow<BleUiState>,
    private val log: (String) -> Unit,
) {
    /** Raises the prompt for [vehicle] when its VIN cannot personalise a session; true when the action must stop. */
    fun request(
        vehicle: Vehicle,
        intent: VinIntent?,
    ): Boolean {
        val request = vinRequestFor(vehicle, intent) ?: return false
        state.update { it.copy(vinRequest = request) }
        log("${vehicle.title}: VIN needed to read or control this car")
        return true
    }

    /** The car screen's banner: the same prompt, with nothing to replay. */
    fun request(vehicle: Vehicle): Boolean = request(vehicle, intent = null)

    /** The owner cancelled: nothing is saved and the blocked action is not replayed. */
    fun dismiss() = state.update { it.copy(vinRequest = null) }

    /** The prompt for [vehicle]'s VIN was just saved: clears it and returns the action to replay, once. */
    fun saved(vehicle: Vehicle?): VinIntent? {
        val pending = state.value.vinRequest?.takeIf { it.bleName == vehicle?.bleName } ?: return null
        state.update { it.copy(vinRequest = null) }
        return replayAfterVin(vehicle, pending)
    }
}
