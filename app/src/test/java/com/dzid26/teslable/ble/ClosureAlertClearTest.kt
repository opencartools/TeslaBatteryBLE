// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosureAlertClearTest {
    @Test
    fun `all shut while the owner is present clears the alert`() {
        assertTrue(clearsWhenShut(closureOpen = false, userPresent = true, locked = false))
    }

    @Test
    fun `all shut with the car locked clears it even when the phone is away`() {
        assertTrue(clearsWhenShut(closureOpen = false, userPresent = false, locked = true))
    }

    @Test
    fun `all shut and unlocked while the phone is away does not clear it`() {
        assertFalse(clearsWhenShut(closureOpen = false, userPresent = false, locked = false))
    }

    @Test
    fun `an open closure never clears it, however secured the car claims to be`() {
        assertFalse(clearsWhenShut(closureOpen = true, userPresent = true, locked = true))
        assertFalse(clearsWhenShut(closureOpen = true, userPresent = false, locked = true))
    }
}
