// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins what a pull to read explains when it cannot send the read. */
class ReadBlockedHintTest {
    private val awake = VehicleStatus(vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE)
    private val asleep = VehicleStatus(vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP)
    private val bothDomains = listOf(Domain.DOMAIN_INFOTAINMENT.name, Domain.DOMAIN_VEHICLE_SECURITY.name)

    private fun connection(
        phase: ConnectionPhase = ConnectionPhase.READY,
        status: VehicleStatus? = awake,
        sessions: List<String> = bothDomains,
    ) = TeslaConnection(address = "AA:BB", name = "car", phase = phase, status = status, sessions = sessions)

    @Test
    fun aLinkThatIsNotReadySaysSo() {
        assertEquals(ReadBlockedHint.NOT_READY, readBlockedHint(null, hasSessionVin = true))
        assertEquals(ReadBlockedHint.NOT_READY, readBlockedHint(connection(phase = ConnectionPhase.CONNECTING), true))
        assertEquals(ReadBlockedHint.NOT_READY, readBlockedHint(connection(phase = ConnectionPhase.DISCONNECTED), true))
    }

    @Test
    fun aReadyLinkBeforeItsFirstStatusSaysNothing() {
        assertNull(readBlockedHint(connection(status = null), hasSessionVin = true))
    }

    @Test
    fun anAsleepCarIsNamed() {
        assertEquals(ReadBlockedHint.ASLEEP, readBlockedHint(connection(status = asleep), true))
    }

    @Test
    fun aMissingVinComesBeforeTheLink() {
        assertEquals(ReadBlockedHint.NEEDS_VIN, readBlockedHint(null, hasSessionVin = false))
    }

    @Test
    fun aMissingVinComesBeforeSleepAndTheSession() {
        assertEquals(
            ReadBlockedHint.NEEDS_VIN,
            readBlockedHint(connection(status = asleep, sessions = emptyList()), hasSessionVin = false),
        )
    }

    @Test
    fun withNoSessionTheToastSaysTheSessionIsComing() {
        assertEquals(
            ReadBlockedHint.NO_SESSION,
            readBlockedHint(connection(sessions = listOf(Domain.DOMAIN_VEHICLE_SECURITY.name)), true),
        )
    }

    @Test
    fun aReadySessionReadsWithoutAComplaint() {
        assertNull(readBlockedHint(connection(), hasSessionVin = true))
    }
}
