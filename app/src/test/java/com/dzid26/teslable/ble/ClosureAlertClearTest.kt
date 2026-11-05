// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosureAlertClearTest {
    @Test
    fun `all shut while the owner is present clears the alert`() {
        assertTrue(clearsWhenShut(closureOpen = false, userPresent = true))
    }

    @Test
    fun `a third party shutting the door while the phone is away does not clear it`() {
        assertFalse(clearsWhenShut(closureOpen = false, userPresent = false))
    }

    @Test
    fun `an open closure never clears it`() {
        assertFalse(clearsWhenShut(closureOpen = true, userPresent = true))
        assertFalse(clearsWhenShut(closureOpen = true, userPresent = false))
    }
}
