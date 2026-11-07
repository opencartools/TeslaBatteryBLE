// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.protocol.TeslaChargeCommands

/** One link's charge-control entry point; the channel owns the queue behind it. */
internal interface ChargeActuator {
    fun actuate(
        key: ActuationKey,
        payload: ByteArray,
    )
}

/**
 * The car screen's charge controls. They are drawn only for a paired car with a VIN, so unlike the
 * pull and the wake they need no VIN prompt of their own; a control tapped after the link went away
 * reports a failure instead.
 */
internal class ChargeActions(
    private val selected: () -> String?,
    private val actuator: (String) -> ChargeActuator?,
    private val log: (String) -> Unit,
    private val publish: (ActuationFeedback) -> Unit,
) {
    fun setLimit(
        percent: Int,
        bleName: String?,
    ) = submit(ActuationKey.CHARGE_LIMIT, bleName, TeslaChargeCommands.buildChargeLimitRequest(percent))

    fun setAmps(
        amps: Int,
        bleName: String?,
    ) = submit(ActuationKey.CHARGING_AMPS, bleName, TeslaChargeCommands.buildChargingAmpsRequest(amps))

    fun start(bleName: String?) = submit(ActuationKey.CHARGE_START, bleName, TeslaChargeCommands.buildChargeStartRequest())

    fun stop(bleName: String?) = submit(ActuationKey.CHARGE_STOP, bleName, TeslaChargeCommands.buildChargeStopRequest())

    private fun submit(
        key: ActuationKey,
        bleName: String?,
        payload: ByteArray,
    ) {
        val name = bleName ?: selected()
        val link = name?.let(actuator)
        if (link == null) {
            log("No connected car for ${key.name.lowercase()}")
            publish(ActuationFeedback(key, accepted = false))
        } else {
            link.actuate(key, payload)
        }
    }
}

/** Sets the charge limit of the selected (or given) car, in percent. */
fun TeslaBleController.setChargeLimit(
    percent: Int,
    bleName: String? = null,
) = chargeActions.setLimit(percent, bleName)

/** Sets the charging current of the selected (or given) car, in amps. */
fun TeslaBleController.setChargingAmps(
    amps: Int,
    bleName: String? = null,
) = chargeActions.setAmps(amps, bleName)

fun TeslaBleController.startCharging(bleName: String? = null) = chargeActions.start(bleName)

fun TeslaBleController.stopCharging(bleName: String? = null) = chargeActions.stop(bleName)
