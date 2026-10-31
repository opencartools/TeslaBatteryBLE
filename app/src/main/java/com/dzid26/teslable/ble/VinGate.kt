// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

/**
 * What the owner asked for and could not get without a VIN. Saving the VIN replays it once, so the tap
 * that raised the prompt is not repeated.
 */
sealed interface VinIntent {
    /** Refresh, read SOC or reconnect: what the link needs now is decided when the replay runs. */
    data object Refresh : VinIntent

    /** Wake the car, from the car screen or the notification. */
    data object Wake : VinIntent

    /** Open the charge port or flash the lights: the car moves, so it needs the same VIN a read does. */
    data class Control(
        val action: QuickAction,
    ) : VinIntent
}

/**
 * The VIN prompt waiting for the owner. [bleName] keeps it with the link that raised it, so a prompt
 * never shows for another car, and [replay] is the action to repeat once the VIN is saved; null for the
 * car screen's banner, which asks for nothing else.
 */
data class VinRequest(
    val bleName: String,
    val replay: VinIntent? = null,
)

/**
 * The prompt [vehicle] needs before [intent] can run, or null when its stored VIN already personalises a
 * session. Only user intent goes through here: background reads on a car with no VIN still skip silently.
 */
fun vinRequestFor(
    vehicle: Vehicle,
    intent: VinIntent?,
): VinRequest? = if (vehicle.hasSessionVin) null else VinRequest(vehicle.bleName, intent)

/** What saving [vehicle]'s VIN from [request] replays: the interrupted action, once a usable VIN is stored. */
fun replayAfterVin(
    vehicle: Vehicle?,
    request: VinRequest?,
): VinIntent? = request?.replay?.takeIf { vehicle?.hasSessionVin == true }
