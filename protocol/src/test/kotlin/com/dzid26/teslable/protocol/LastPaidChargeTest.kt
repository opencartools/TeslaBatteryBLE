// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.protocol

import com.tesla.generated.carserver.vehicle.ChargeState
import okio.ByteString.Companion.toByteString
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LastPaidChargeTest {
    /** A charge reply as the car sends it. */
    private fun reply(withTime: Boolean = true) =
        ChargeState(
            battery_level = 80,
            last_charge_cost = 4.22f,
            last_charge_currency = "EUR",
            last_charge_price_per_kwh = 0.28f,
            last_charge_timestamp = if (withTime) Instant.ofEpochSecond(1_780_411_979) else null,
        )

    /** Bytes exactly as the car sent them, from the fork's `charge_state_test.go`. */
    private fun wire(hex: String) =
        hex
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
            .toByteString()

    @Test
    fun `the last paid charge is read from the reply, as the owner's invoice shows it`() {
        val state = ChargeState.ADAPTER.decode(ChargeState.ADAPTER.encode(reply()))

        assertEquals(80, state.battery_level)
        assertEquals(LastPaidCharge(1_780_411_979_000, 4.22f, "EUR", 0.28f), state.lastPaidCharge)
    }

    @Test
    fun `the car's captured reply carries the last paid charge in the defined fields`() {
        val state =
            ChargeState.ADAPTER.decode(wire("bd0b3d0a8740c20b03455552cd0b295c8f3ef20b0608cbdcfbd006"))

        assertEquals(LastPaidCharge(1_780_411_979_000, 4.22f, "EUR", 0.28f), state.lastPaidCharge)
    }

    @Test
    fun `the car's captured reply without the timestamp field has no paid charge`() {
        val state =
            ChargeState.ADAPTER.decode(wire("bd0b3d0a8740c20b03455552cd0b295c8f3e"))

        assertNull(state.lastPaidCharge)
    }

    @Test
    fun `a reply without the cost, currency and time has no paid charge`() {
        assertNull(ChargeState(battery_level = 80).lastPaidCharge)
        assertNull(reply(withTime = false).lastPaidCharge)
    }
}
