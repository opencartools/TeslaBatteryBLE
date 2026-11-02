// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosureAlertMaskTest {
    private val open = ClosureState_E.CLOSURESTATE_OPEN

    @Test
    fun `an open door counts`() {
        assertTrue(ClosureStatuses(frontDriverDoor = open).anyClosureOpen())
        assertTrue(ClosureStatuses(rearPassengerDoor = open).anyClosureOpen())
    }

    @Test
    fun `an open trunk counts, including the frunk while locked`() {
        assertTrue(ClosureStatuses(frontTrunk = open).anyClosureOpen())
        assertTrue(ClosureStatuses(rearTrunk = open).anyClosureOpen())
        assertTrue(ClosureStatuses(tonneau = open).anyClosureOpen())
    }

    @Test
    fun `an open charge port alone does not count`() {
        assertFalse(ClosureStatuses(chargePort = open).anyClosureOpen())
    }

    @Test
    fun `all closures closed does not count`() {
        assertFalse(ClosureStatuses().anyClosureOpen())
    }

    @Test
    fun `unknown does not count`() {
        assertFalse(ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_UNKNOWN).anyClosureOpen())
    }

    @Test
    fun `an opening or ajar closure counts`() {
        assertTrue(ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_OPENING).anyClosureOpen())
        assertTrue(ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_AJAR).anyClosureOpen())
    }

    @Test
    fun `a closing or failed-to-latch closure does not count`() {
        assertFalse(ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_CLOSING).anyClosureOpen())
        assertFalse(ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_FAILED_UNLATCH).anyClosureOpen())
    }

    @Test
    fun `no closure statuses at all does not count`() {
        assertFalse((null as ClosureStatuses?).anyClosureOpen())
    }
}
