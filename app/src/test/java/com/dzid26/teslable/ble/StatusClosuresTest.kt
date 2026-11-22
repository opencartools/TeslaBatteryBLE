// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The live status keeps the last closures when an asleep status omits them. */
class StatusClosuresTest {
    @Test
    fun `a status that carries closures keeps them`() {
        val previous = status(ClosureState_E.CLOSURESTATE_CLOSED)
        val next = status(ClosureState_E.CLOSURESTATE_OPEN)
        val merged = next.withLastKnownClosures(previous)
        assertEquals(ClosureState_E.CLOSURESTATE_OPEN, merged.closureStatuses?.chargePort)
    }

    @Test
    fun `an asleep status without closures keeps the last reported ones`() {
        val previous = status(ClosureState_E.CLOSURESTATE_OPEN)
        val asleep = VehicleStatus(vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP)
        val merged = asleep.withLastKnownClosures(previous)
        assertEquals(ClosureState_E.CLOSURESTATE_OPEN, merged.closureStatuses?.chargePort)
        assertEquals(VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP, merged.vehicleSleepStatus)
    }

    @Test
    fun `without a previous status the closures stay unknown`() {
        val asleep = VehicleStatus(vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP)
        assertNull(asleep.withLastKnownClosures(null).closureStatuses)
    }

    private fun status(port: ClosureState_E) = VehicleStatus(closureStatuses = ClosureStatuses(chargePort = port))
}
