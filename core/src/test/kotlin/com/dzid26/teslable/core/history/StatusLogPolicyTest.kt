// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.squareup.wire.ofEpochSecond
import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusLogPolicyTest {
    private val asleep =
        VehicleStatus(
            vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED,
            vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP,
            userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT,
        )

    private val inUse =
        VehicleStatus(
            vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED,
            vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE,
            userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_PRESENT,
        )

    /** The last logged status record, acquired [minutes] after the epoch. */
    private fun logged(
        minutes: Long,
        status: VehicleStatus = asleep,
    ) = BleRecord(device_timestamp = ofEpochSecond(minutes * 60, 0), vehicle_status = status)

    /** The phone's clock [millis] after the epoch. */
    private fun at(millis: Long) = ofEpochSecond(millis / 1_000, millis % 1_000 * 1_000_000)

    @Test
    fun `the first reading for a vehicle is logged`() {
        assertTrue(shouldLogStatus(last = null, status = asleep, deviceTimestamp = at(0), firstAfterConnect = false))
    }

    @Test
    fun `the first reading after a reconnect is logged even when nothing changed`() {
        assertTrue(shouldLogStatus(logged(0), asleep, deviceTimestamp = at(MINUTE), firstAfterConnect = true))
        assertFalse(shouldLogStatus(logged(0), asleep, deviceTimestamp = at(MINUTE / 2), firstAfterConnect = false))
    }

    @Test
    fun `a changed status is logged right away`() {
        assertTrue(shouldLogStatus(logged(0), inUse, deviceTimestamp = at(10_000), firstAfterConnect = false))
    }

    @Test
    fun `any raw difference counts as a change, not just the parsed flags`() {
        // Locked, asleep and away as before; only a closure differs.
        val portOpen = asleep.copy(closureStatuses = ClosureStatuses(chargePort = ClosureState_E.CLOSURESTATE_OPEN))
        assertTrue(shouldLogStatus(logged(0), portOpen, deviceTimestamp = at(10_000), firstAfterConnect = false))
    }

    @Test
    fun `an unchanged status waits for the one minute heartbeat`() {
        val last = logged(0)
        assertFalse(shouldLogStatus(last, asleep, deviceTimestamp = at(MINUTE - 1), firstAfterConnect = false))
        assertTrue(shouldLogStatus(last, asleep, deviceTimestamp = at(MINUTE), firstAfterConnect = false))
    }

    @Test
    fun `a reading acquired before the last logged one is logged`() {
        // The phone's clock moved back; how much time passed is unknown.
        assertTrue(shouldLogStatus(logged(60), asleep, deviceTimestamp = at(59 * MINUTE), firstAfterConnect = false))
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
