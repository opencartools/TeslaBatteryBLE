// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Follows the Bluetooth switch for the life of the process. Turning off and off are one event: a link
 * drop while the radio goes is the switch, not the owner walking away, so the alert treats them alike.
 */
internal class BluetoothStateReceiver(
    private val onOff: () -> Unit,
    private val onOn: () -> Unit,
) : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
            BluetoothAdapter.STATE_TURNING_OFF,
            BluetoothAdapter.STATE_OFF -> onOff()

            BluetoothAdapter.STATE_ON -> onOn()
        }
    }
}
