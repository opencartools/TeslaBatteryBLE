// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.protocol

import com.squareup.wire.ProtoAdapter
import com.squareup.wire.ProtoWriter
import com.tesla.generated.carserver.vehicle.ChargeState
import okio.Buffer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LastPaidChargeTest {
    /** A charge reply as the car sends it, with the last paid charge's fields that the pinned protos lack. */
    private fun reply(withTime: Boolean = true): ChargeState {
        val bytes = Buffer()
        val writer = ProtoWriter(bytes)
        ChargeState.ADAPTER.encode(writer, ChargeState(battery_level = 80))
        ProtoAdapter.FLOAT.encodeWithTag(writer, 183, 4.22f)
        ProtoAdapter.STRING.encodeWithTag(writer, 184, "EUR")
        ProtoAdapter.FLOAT.encodeWithTag(writer, 185, 0.28f)
        if (withTime) ProtoAdapter.INSTANT.encodeWithTag(writer, 190, Instant.ofEpochSecond(1_780_411_979))
        ProtoAdapter.INT32.encodeWithTag(writer, 186, 20)
        return ChargeState.ADAPTER.decode(bytes.readByteString())
    }

    @Test
    fun `the last paid charge is read from the reply's undefined fields, as the owner's invoice shows it`() {
        val state = reply()

        assertEquals(80, state.battery_level)
        assertEquals(LastPaidCharge(1_780_411_979_000, 4.22f, "EUR", 0.28f), state.lastPaidCharge)
    }

    @Test
    fun `a reply without the cost, currency and time has no paid charge`() {
        assertNull(ChargeState(battery_level = 80).lastPaidCharge)
        assertNull(reply(withTime = false).lastPaidCharge)
    }
}
