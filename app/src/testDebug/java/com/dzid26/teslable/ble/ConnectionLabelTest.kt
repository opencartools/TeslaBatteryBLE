// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionLabelTest {
    private val awake = VehicleStatus(vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE)
    private val asleep = VehicleStatus(vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP)
    private val zzz = String(Character.toChars(0x1F4A4))

    private fun connection(
        phase: ConnectionPhase,
        status: VehicleStatus? = null,
        pausedUntilMillis: Long? = null,
    ) = TeslaConnection(
        address = "AA:BB",
        name = "car",
        phase = phase,
        status = status,
        pausedUntilMillis = pausedUntilMillis,
    )

    private fun pillAndLine(connection: TeslaConnection?) =
        connectionLabel(connection).pillText to connectionDisplay(connection, advert = null).stateText

    @Test
    fun `every phase and status reads the same on the pill and the status line`() {
        // A car keeps its last status across a drop, so every phase is checked with no status, awake and asleep.
        val disconnected = "Disconnected" to "Disconnected"
        val connecting = "Connecting" to "Connecting..."
        val expected =
            mapOf(
                ConnectionPhase.IDLE to List(3) { disconnected },
                ConnectionPhase.DISCONNECTED to List(3) { disconnected },
                ConnectionPhase.FAILED to List(3) { "Failed" to "Disconnected" },
                ConnectionPhase.CONNECTING to List(3) { connecting },
                // The link is up but still being set up; the pill used to say Disconnected here.
                ConnectionPhase.CONNECTED to List(3) { connecting },
                ConnectionPhase.DISCOVERING to List(3) { connecting },
                // Ready before the first status arrives; the pill used to say Disconnected here too.
                ConnectionPhase.READY to
                    listOf("Connected" to "Connected", "Awake" to "Connected", "Asleep" to "Connected $zzz"),
            )
        assertEquals(ConnectionPhase.entries.toSet(), expected.keys)
        for ((phase, labels) in expected) {
            listOf(null, awake, asleep).zip(labels).forEach { (status, want) ->
                assertEquals("$phase with status $status", want, pillAndLine(connection(phase, status)))
            }
        }
    }

    @Test
    fun `no connection at all reads disconnected`() {
        assertEquals("Disconnected" to "Disconnected", pillAndLine(null))
    }

    @Test
    fun awakePausedCarReadsPausedOnThePillAndStaysConnected() {
        assertEquals(
            "awake and paused",
            "Paused" to "Connected",
            pillAndLine(connection(ConnectionPhase.READY, awake, pausedUntilMillis = 1_000L)),
        )
        assertEquals(
            "a sleeping car reads Asleep whatever the pause says",
            "Asleep" to "Connected $zzz",
            pillAndLine(connection(ConnectionPhase.READY, asleep, pausedUntilMillis = 1_000L)),
        )
        assertEquals(
            "only a ready link can be paused",
            "Connecting" to "Connecting...",
            pillAndLine(connection(ConnectionPhase.CONNECTING, awake, pausedUntilMillis = 1_000L)),
        )
    }

    @Test
    fun bluetoothOffOverridesEveryPhaseOnPillAndStatusLine() {
        for (phase in ConnectionPhase.entries) {
            val connection = connection(phase, awake)
            assertEquals(
                phase.name,
                "Bluetooth off" to "Bluetooth off",
                connectionLabel(connection, bluetoothOn = false).pillText to
                    connectionDisplay(connection, advert = null, bluetoothOn = false).stateText,
            )
        }
    }
}
