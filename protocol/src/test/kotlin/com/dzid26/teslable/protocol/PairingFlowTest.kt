// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import com.tesla.generated.keys.Role
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.KeyIdentifier
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.WhitelistEntryInfo
import com.tesla.generated.vcsec.WhitelistInfo
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingFlowTest {
    private val keyPair = TeslaKeyPair(privateKeyPkcs8 = byteArrayOf(1), publicKeyRaw = ByteArray(65) { it.toByte() })
    private val keys = FakeKeys()
    private val host = FakeHost()
    private val flow = PairingFlow(keys, host)

    @Test
    fun `first pairing generates a key, sends it and starts both timers`() {
        flow.pairKey()

        assertEquals(PairingPhase.SENDING, flow.phase)
        assertEquals(1, keys.generated)
        assertEquals(1, host.addKeyRequests.size)
        assertEquals(listOf<Int?>(null), host.keySlots)
        assertEquals(setOf(PairingTimer.RESPONSE_TIMEOUT, PairingTimer.WHITELIST_POLL), host.timers.keys)
    }

    @Test
    fun `pairing does nothing while the link is not ready`() {
        host.isReady = false

        flow.pairKey()

        assertEquals(PairingPhase.IDLE, flow.phase)
        assertEquals(0, keys.generated)
        assertTrue(host.addKeyRequests.isEmpty())
    }

    @Test
    fun `a key that could not be saved is never sent`() {
        keys.generateFails = true

        flow.pairKey()

        assertEquals(PairingPhase.ERROR, flow.phase)
        assertTrue(host.addKeyRequests.isEmpty())
    }

    @Test
    fun `an unsent add-key request is an error and starts no timers`() {
        host.addKeyAccepted = false

        flow.pairKey()

        assertEquals(PairingPhase.ERROR, flow.phase)
        assertTrue(host.timers.isEmpty())
    }

    @Test
    fun `no reply to the add-key request means waiting for the card`() {
        flow.pairKey()

        flow.onTimer(PairingTimer.RESPONSE_TIMEOUT)

        assertEquals(PairingPhase.WAITING_FOR_CARD, flow.phase)
    }

    @Test
    fun `the car confirming the key saves the vehicle and looks up the slot`() {
        flow.pairKey()
        keys.stored = keyPair

        assertTrue(flow.onMessage(addKeyResponse(OperationStatus_E.OPERATIONSTATUS_OK)))

        assertEquals(PairingPhase.OK, flow.phase)
        assertEquals(1, host.confirmed)
        assertEquals(PairingReason.KEY_LOOKUP, host.vcsecRequests.last().second)
        assertFalse(PairingTimer.RESPONSE_TIMEOUT in host.timers)
    }

    @Test
    fun `the whitelist poll gives up after the attempt limit`() {
        flow.pairKey()

        repeat(PairingFlow.WHITELIST_MAX_ATTEMPTS) { flow.onTimer(PairingTimer.WHITELIST_POLL) }
        assertEquals(PairingPhase.SENDING, flow.phase)
        flow.onTimer(PairingTimer.WHITELIST_POLL)

        assertEquals(PairingPhase.ERROR, flow.phase)
    }

    @Test
    fun `a stored key the car still lists is confirmed without adding it again`() {
        keys.stored = keyPair

        flow.pairKey()
        assertEquals(PairingPhase.CHECKING, flow.phase)
        flow.onMessage(whitelist(listOf(keyPair.keyId), slotMask = 0b100))

        assertEquals(PairingPhase.OK, flow.phase)
        assertTrue(host.addKeyRequests.isEmpty())
        assertEquals(1, host.freshlyEnrolled)
        assertEquals(1, host.confirmed)
        // The slot lookup starts with the slot the key's list position points at.
        assertEquals(PairingReason.KEY_LOOKUP, host.vcsecRequests.last().second)
    }

    @Test
    fun `a stored key the car dropped is replaced, never re-enrolled`() {
        keys.stored = keyPair

        flow.pairKey()
        flow.onMessage(whitelist(listOf(byteArrayOf(9, 9, 9, 9)), slotMask = 0b1))

        assertEquals(1, keys.generated)
        assertEquals(PairingPhase.SENDING, flow.phase)
        assertEquals(1, host.addKeyRequests.size)
    }

    @Test
    fun `no reply while checking the key list is an error`() {
        keys.stored = keyPair
        flow.pairKey()

        flow.onTimer(PairingTimer.RESPONSE_TIMEOUT)

        assertEquals(PairingPhase.ERROR, flow.phase)
        // The check is over: a late list that lacks the key must not start a new enrollment.
        flow.onMessage(whitelist(listOf(byteArrayOf(9, 9, 9, 9)), slotMask = 0b1))
        assertEquals(0, keys.generated)
    }

    @Test
    fun `a matching slot entry is published and clears a stale error`() {
        keys.stored = keyPair
        flow.pairKey()
        flow.onTimer(PairingTimer.RESPONSE_TIMEOUT)
        assertEquals(PairingPhase.ERROR, flow.phase)

        flow.onMessage(slotEntry(slot = 5, keyId = keyPair.keyId, role = Role.ROLE_CHARGING_MANAGER))

        assertEquals(5, host.keySlots.last())
        // The role the car has for this key decides which actions it may authorize.
        assertEquals(Role.ROLE_CHARGING_MANAGER, host.keyRoles.last())
        assertEquals(PairingPhase.IDLE, flow.phase)
    }

    @Test
    fun `a slot entry for another key moves on to the next slot`() {
        keys.stored = keyPair
        flow.pairKey()
        flow.onMessage(whitelist(listOf(keyPair.keyId, byteArrayOf(7, 7, 7, 7)), slotMask = 0b101))
        val before = host.vcsecRequests.size

        flow.onMessage(slotEntry(slot = 0, keyId = byteArrayOf(7, 7, 7, 7)))

        assertEquals(before + 1, host.vcsecRequests.size)
        assertNull(host.keySlots.lastOrNull())
    }

    @Test
    fun `only a whole key list proves the car dropped our key`() {
        keys.stored = keyPair
        host.shownSlot = true

        // Two entries announced, one listed: incomplete.
        flow.onMessage(whitelist(listOf(byteArrayOf(9, 9, 9, 9)), slotMask = 0b11, numberOfEntries = 2))
        assertTrue(host.keySlots.isEmpty())

        flow.onMessage(whitelist(listOf(byteArrayOf(9, 9, 9, 9)), slotMask = 0b1))
        assertEquals(listOf<Int?>(null), host.keySlots)
    }

    @Test
    fun `forgetting an enrollment that nothing shows does nothing`() {
        flow.forgetEnrollment("test")

        assertTrue(host.keySlots.isEmpty())
        assertTrue(host.logs.none { it.contains("pair again") })
    }

    @Test
    fun `the slot lookup is skipped when the link shows a slot or there is no key`() {
        flow.requestKeySlot()
        assertTrue(host.vcsecRequests.isEmpty())

        keys.stored = keyPair
        host.connectionSlot = true
        flow.requestKeySlot()
        assertTrue(host.vcsecRequests.isEmpty())

        host.connectionSlot = false
        flow.requestKeySlot()
        assertEquals(1, host.vcsecRequests.size)
    }

    @Test
    fun `a command status with no enrollment under way is not a pairing result`() {
        assertFalse(flow.onMessage(addKeyResponse(OperationStatus_E.OPERATIONSTATUS_ERROR)))

        assertEquals(PairingPhase.IDLE, flow.phase)
        assertTrue(host.vcsecRequests.isEmpty())
    }

    @Test
    fun `a late command status after pairing finished is ignored`() {
        flow.pairKey()
        keys.stored = keyPair
        flow.onMessage(addKeyResponse(OperationStatus_E.OPERATIONSTATUS_OK))
        val requests = host.vcsecRequests.size

        assertFalse(flow.onMessage(addKeyResponse(OperationStatus_E.OPERATIONSTATUS_ERROR)))

        assertEquals(PairingPhase.OK, flow.phase)
        assertEquals(requests, host.vcsecRequests.size)
    }

    @Test
    fun `messages that are not pairing replies are left to the caller`() {
        assertFalse(flow.onMessage(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `clearing the cache returns to idle`() {
        flow.pairKey()

        flow.onCacheCleared()

        assertEquals(PairingPhase.IDLE, flow.phase)
    }

    private fun addKeyResponse(status: OperationStatus_E): ByteArray {
        val payload = FromVCSECMessage(commandStatus = CommandStatus(operationStatus = status)).encode()
        return RoutableMessage(protobuf_message_as_bytes = payload.toByteString()).encode()
    }

    private fun whitelist(
        keyIds: List<ByteArray>,
        slotMask: Int,
        numberOfEntries: Int = keyIds.size,
    ): ByteArray {
        val payload =
            FromVCSECMessage(
                whitelistInfo =
                    WhitelistInfo(
                        numberOfEntries = numberOfEntries,
                        slotMask = slotMask,
                        whitelistEntries = keyIds.map { KeyIdentifier(publicKeySHA1 = it.toByteString()) },
                    ),
            ).encode()
        return RoutableMessage(protobuf_message_as_bytes = payload.toByteString()).encode()
    }

    private fun slotEntry(
        slot: Int,
        keyId: ByteArray,
        role: Role = Role.ROLE_NONE,
    ): ByteArray {
        val payload =
            FromVCSECMessage(
                whitelistEntryInfo =
                    WhitelistEntryInfo(
                        keyId = KeyIdentifier(publicKeySHA1 = keyId.toByteString()),
                        slot = slot,
                        keyRole = role,
                    ),
            ).encode()
        return RoutableMessage(protobuf_message_as_bytes = payload.toByteString()).encode()
    }

    private inner class FakeKeys : PairingKeys {
        var stored: TeslaKeyPair? = null
        var generated = 0
        var generateFails = false

        override fun hasKey() = stored != null

        override fun publicKeyRaw() = stored?.publicKeyRaw

        override fun keyId() = stored?.keyId

        override fun generate(): TeslaKeyPair? {
            generated++
            if (generateFails) return null
            stored = keyPair
            return keyPair
        }
    }

    private class FakeHost : PairingHost {
        override var isReady = true
        var addKeyAccepted = true
        var connectionSlot = false
        var shownSlot = false
        var confirmed = 0
        var freshlyEnrolled = 0
        val logs = mutableListOf<String>()
        val vcsecRequests = mutableListOf<Pair<ByteArray, PairingReason>>()
        val addKeyRequests = mutableListOf<ByteArray>()
        val keySlots = mutableListOf<Int?>()
        val keyRoles = mutableListOf<Role>()
        val timers = mutableMapOf<PairingTimer, Long>()

        override fun log(message: String) {
            logs += message
        }

        override fun transmitVcsec(
            request: ByteArray,
            reason: PairingReason,
        ) {
            vcsecRequests += request to reason
        }

        override fun transmitAddKey(request: ByteArray): Boolean {
            if (addKeyAccepted) addKeyRequests += request
            return addKeyAccepted
        }

        override fun publishPairing(
            phase: PairingPhase,
            keyId: String?,
        ) = Unit

        override fun publishKeySlot(slot: Int?) {
            keySlots += slot
        }

        override fun publishKeyRole(role: Role) {
            keyRoles += role
        }

        override fun hasConnectionKeySlot() = connectionSlot

        override fun hasShownKeySlot() = shownSlot || connectionSlot

        override fun onKeyConfirmed() {
            confirmed++
        }

        override fun onFreshlyEnrolled() {
            freshlyEnrolled++
        }

        override fun schedule(
            timer: PairingTimer,
            delayMs: Long,
        ) {
            timers[timer] = delayMs
        }

        override fun cancel(timer: PairingTimer) {
            timers.remove(timer)
        }
    }
}
