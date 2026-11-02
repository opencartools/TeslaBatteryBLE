// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import com.tesla.generated.vcsec.WhitelistEntryInfo
import com.tesla.generated.vcsec.WhitelistInfo

enum class PairingPhase {
    IDLE,
    CHECKING,
    SENDING,
    WAITING_FOR_CARD,
    OK,
    ERROR,
}

/** The two timers a [PairingFlow] asks its host to run. */
enum class PairingTimer {
    /** No reply to the add-key request, or to the whitelist check, within [PairingFlow.RESPONSE_TIMEOUT_MS]. */
    RESPONSE_TIMEOUT,

    /** Asks the car for its key list again while the add-key request waits for the card. */
    WHITELIST_POLL,
}

/** Why a [PairingFlow] asks the car for its key list or a key slot. */
enum class PairingReason {
    /** Checking whether the car already has this phone's key, or whether the add-key request went through. */
    PAIRING,

    /** Finding which key slot holds this phone's key. */
    KEY_LOOKUP,
}

/** The pairing key of one car, as the platform key store holds it. */
interface PairingKeys {
    fun hasKey(): Boolean

    fun publicKeyRaw(): ByteArray?

    /** The short SHA-1 identifier the car lists for this key. */
    fun keyId(): ByteArray?

    /** Replaces the car's key with a fresh one; null when it could not be saved. */
    fun generate(): TeslaKeyPair?
}

/**
 * What a [PairingFlow] needs from the link that owns it: a way to send,
 * somewhere to publish its state, and two timers. Everything platform-bound
 * stays behind this seam (ADR-0007).
 */
interface PairingHost {
    /** The link is ready to exchange VCSEC messages. */
    val isReady: Boolean

    fun log(message: String)

    fun transmitVcsec(
        request: ByteArray,
        reason: PairingReason,
    )

    /** Sends the add-key request; false when it could not be sent. */
    fun transmitAddKey(request: ByteArray): Boolean

    fun publishPairing(
        phase: PairingPhase,
        keyId: String?,
    )

    /** Shows [slot] as this phone's key slot on the link and in the saved vehicle; null clears it. */
    fun publishKeySlot(slot: Int?)

    /** The link already shows a key slot (used to skip a redundant lookup). */
    fun hasConnectionKeySlot(): Boolean

    /** The link or the saved vehicle shows a key slot, so the car looks paired. */
    fun hasShownKeySlot(): Boolean

    /** The car has this phone's key: save the vehicle and open its sessions. */
    fun onKeyConfirmed()

    /** The car just confirmed an enrollment that was under way, so its reads start from fresh. */
    fun onFreshlyEnrolled()

    /** Runs [PairingFlow.onTimer] for [timer] after [delayMs]; replaces a pending run of the same timer. */
    fun schedule(
        timer: PairingTimer,
        delayMs: Long,
    )

    fun cancel(timer: PairingTimer)
}

/**
 * The pairing state machine of one car: enrolling the phone's key, checking
 * whether the car already has it, and finding the key slot. The host feeds it
 * the car's replies and timer ticks; it answers through [PairingHost].
 */
class PairingFlow(
    private val keys: PairingKeys,
    private val host: PairingHost,
) {
    var phase: PairingPhase = PairingPhase.IDLE
        private set

    private var keyId: String? = null
    private var pendingPairCheck = false
    private var whitelistPollAttempts = 0
    private var keySlotQueue: List<Int> = emptyList()

    fun pairKey() {
        if (!host.isReady) {
            host.log("No selected car ready for pairing")
            return
        }
        val storedKeyId = keys.keyId()
        if (storedKeyId == null) {
            // First pairing for this car: generate its key now.
            pairWithFreshKey()
            return
        }
        // The car may already have this key. Adding it again would ask for
        // the card and can fail, so check the whitelist first.
        pendingPairCheck = true
        keyId = storedKeyId.toHexString()
        setPairing(PairingPhase.CHECKING)
        host.log("checking whether the key is already enrolled")
        host.transmitVcsec(TeslaVcsec.buildWhitelistInfoRequest(), PairingReason.PAIRING)
        host.schedule(PairingTimer.RESPONSE_TIMEOUT, RESPONSE_TIMEOUT_MS)
    }

    /** Looks up which slot holds this phone's key, unless the link already shows it. */
    fun requestKeySlot() {
        if (host.hasConnectionKeySlot()) return
        if (!keys.hasKey()) return
        host.transmitVcsec(TeslaVcsec.buildWhitelistInfoRequest(), PairingReason.KEY_LOOKUP)
    }

    /** Handles a pairing or whitelist reply; false when [message] is none of them. */
    fun onMessage(message: ByteArray): Boolean {
        if (handlePairingResponse(message)) return true
        val whitelist = runCatching { TeslaVcsec.parseWhitelistInfoResponse(message) }.getOrNull()
        if (whitelist != null) {
            handleWhitelistInfo(whitelist)
            return true
        }
        val entry = runCatching { TeslaVcsec.parseWhitelistEntryResponse(message) }.getOrNull()
        if (entry != null) {
            handleWhitelistEntry(entry)
            return true
        }
        return false
    }

    fun onTimer(timer: PairingTimer) {
        when (timer) {
            PairingTimer.RESPONSE_TIMEOUT -> onResponseTimeout()
            PairingTimer.WHITELIST_POLL -> onWhitelistPoll()
        }
    }

    /**
     * Once this car's key is known to be unusable, drops what still shows
     * the car as paired (cached slot, a stale OK) so it offers pairing again.
     */
    fun forgetEnrollment(reason: String) {
        val shownPaired = host.hasShownKeySlot() || phase == PairingPhase.OK
        if (!shownPaired) return
        host.publishKeySlot(null)
        if (phase == PairingPhase.OK) setPairing(PairingPhase.IDLE, null)
        host.log("$reason; pair again")
    }

    fun cancelTimers() {
        host.cancel(PairingTimer.WHITELIST_POLL)
        host.cancel(PairingTimer.RESPONSE_TIMEOUT)
    }

    /** The link closed: a lookup or check in flight will never be answered. */
    fun onLinkClosed() {
        keySlotQueue = emptyList()
        pendingPairCheck = false
    }

    /** The pairing cache was cleared; the host has already reset what it shows. */
    fun onCacheCleared() {
        onLinkClosed()
        phase = PairingPhase.IDLE
        keyId = null
    }

    /**
     * Starts the add-key flow with a freshly generated key for this car.
     * A key the car no longer recognizes is never re-enrolled: it may have
     * been removed after a phone theft, and re-enrolling it would re-arm
     * the stolen copy.
     */
    private fun pairWithFreshKey() {
        val keyPair = keys.generate()
        if (keyPair == null) {
            // Enrolling a key the app may not have kept would orphan the
            // car's key slot, so pairing stops here.
            setPairing(PairingPhase.ERROR)
            host.log("could not save a new key; pairing not started")
            return
        }
        // The previous key is gone; its cached slot is stale.
        host.publishKeySlot(null)
        sendAddKey(keyPair)
    }

    private fun sendAddKey(keyPair: TeslaKeyPair) {
        val id = keyPair.keyId.toHexString()
        setPairing(PairingPhase.SENDING, id)
        host.log("pairing key $id")
        if (!host.transmitAddKey(TeslaPairing.buildAddKeyRequest(keyPair.publicKeyRaw))) {
            setPairing(PairingPhase.ERROR)
            host.log("failed to send pairing request")
        } else {
            host.schedule(PairingTimer.RESPONSE_TIMEOUT, RESPONSE_TIMEOUT_MS)
            whitelistPollAttempts = 0
            host.schedule(PairingTimer.WHITELIST_POLL, WHITELIST_POLL_MS)
        }
    }

    private fun setPairing(
        newPhase: PairingPhase,
        newKeyId: String? = keyId,
    ) {
        phase = newPhase
        keyId = newKeyId
        host.publishPairing(newPhase, newKeyId)
    }

    private fun onResponseTimeout() {
        when (phase) {
            PairingPhase.SENDING -> {
                setPairing(PairingPhase.WAITING_FOR_CARD)
                host.log("no pairing response yet; waiting for the card")
            }

            PairingPhase.CHECKING -> {
                pendingPairCheck = false
                setPairing(PairingPhase.ERROR)
                host.log("no response while checking the key list")
            }

            else -> Unit
        }
    }

    private fun onWhitelistPoll() {
        if (phase == PairingPhase.OK || phase == PairingPhase.ERROR) return
        if (whitelistPollAttempts++ >= WHITELIST_MAX_ATTEMPTS) {
            setPairing(PairingPhase.ERROR)
            host.log("the car did not confirm the key; pair again when ready")
            return
        }
        host.transmitVcsec(TeslaVcsec.buildWhitelistInfoRequest(), PairingReason.PAIRING)
        host.schedule(PairingTimer.WHITELIST_POLL, WHITELIST_POLL_MS)
    }

    private fun requestNextKeySlot() {
        val slot = keySlotQueue.firstOrNull()
        if (slot == null) {
            host.log("could not locate our key slot")
            return
        }
        keySlotQueue = keySlotQueue.drop(1)
        host.transmitVcsec(TeslaVcsec.buildWhitelistEntryRequest(slot), PairingReason.KEY_LOOKUP)
    }

    private fun handlePairingResponse(message: ByteArray): Boolean {
        // Any VCSEC reply can carry a command status (an error to a status request, say);
        // it is an add-key result only while an enrollment waits for one.
        val pairing =
            if (phase in AWAITING_ADD_KEY_RESULT) {
                runCatching { TeslaPairing.parseAddKeyResponse(message) }.getOrNull()
            } else {
                null
            }
        if (pairing == null) return false
        host.cancel(PairingTimer.RESPONSE_TIMEOUT)
        val newPhase =
            when (pairing) {
                TeslaPairing.Result.OK -> PairingPhase.OK
                TeslaPairing.Result.WAITING_FOR_CARD -> PairingPhase.WAITING_FOR_CARD
                TeslaPairing.Result.ERROR -> PairingPhase.ERROR
            }
        setPairing(newPhase)
        when (newPhase) {
            PairingPhase.OK -> {
                // The car confirmed the key: save the vehicle, open a
                // session, and pull the whitelist so the slot resolves.
                host.onKeyConfirmed()
                requestKeySlot()
            }

            PairingPhase.ERROR -> {
                // The car may already have the key; a whitelist check
                // clears the failure if it is enrolled.
                requestKeySlot()
            }

            else -> Unit
        }
        host.log("pairing ${pairing.name.lowercase()}")
        return true
    }

    private fun handleWhitelistInfo(whitelist: WhitelistInfo) {
        val storedKeyId = keys.keyId()
        val enrolled = storedKeyId != null && whitelist.lists(storedKeyId)
        if (storedKeyId != null && enrolled) {
            host.cancel(PairingTimer.WHITELIST_POLL)
            pendingPairCheck = false
            when (phase) {
                PairingPhase.SENDING, PairingPhase.WAITING_FOR_CARD, PairingPhase.CHECKING -> {
                    setPairing(PairingPhase.OK, storedKeyId.toHexString())
                    host.onFreshlyEnrolled()
                    host.log("key enrolled (${whitelist.numberOfEntries} keys)")
                }

                PairingPhase.ERROR -> {
                    // The car has the key after all; drop the stale failure.
                    setPairing(PairingPhase.IDLE, null)
                    host.log("key is enrolled; pairing state recovered")
                }

                else -> Unit
            }
            // A freshly enrolled key can open sessions now.
            host.onKeyConfirmed()
            resolveKeySlots(whitelist, storedKeyId)
            return
        }
        if (pendingPairCheck) {
            // Never re-enroll a key the car no longer has: it may have been
            // removed after a theft, so pairing enrolls a fresh key.
            pendingPairCheck = false
            host.log("key not enrolled; generating a new key for pairing")
            pairWithFreshKey()
            return
        }
        if (storedKeyId != null && !enrolled && whitelist.isComplete(storedKeyId.size)) {
            // Only a whole listing proves the car no longer has the key
            // (removed in the car, or restored here from another phone).
            forgetEnrollment("the car no longer lists this phone's key")
        }
        if (whitelistPollAttempts == 1) {
            host.log("whitelist has ${whitelist.numberOfEntries} keys")
        }
    }

    private fun resolveKeySlots(
        whitelist: WhitelistInfo,
        storedKeyId: ByteArray,
    ) {
        val index = whitelist.whitelistEntries.indexOfFirst { it.publicKeySHA1.toByteArray().startsWithId(storedKeyId) }
        val slots = occupiedSlots(whitelist.slotMask)
        keySlotQueue =
            if (index in slots.indices) {
                listOf(slots[index]) + slots.filterIndexed { i, _ -> i != index }
            } else {
                slots
            }
        requestNextKeySlot()
    }

    private fun handleWhitelistEntry(entry: WhitelistEntryInfo) {
        val storedPublicKey = keys.publicKeyRaw()
        val storedKeyId = keys.keyId()
        val matches =
            storedPublicKey != null &&
                storedKeyId != null &&
                (
                    entry.publicKey
                        ?.PublicKeyRaw
                        ?.toByteArray()
                        ?.contentEquals(storedPublicKey) == true ||
                        entry.keyId
                            ?.publicKeySHA1
                            ?.toByteArray()
                            ?.startsWithId(storedKeyId) == true
                )
        if (matches) {
            keySlotQueue = emptyList()
            host.publishKeySlot(entry.slot)
            if (phase == PairingPhase.ERROR) {
                // The slot proves enrollment; clear the stale failure.
                setPairing(PairingPhase.IDLE, null)
            }
            host.log("key slot ${entry.slot}")
        } else {
            requestNextKeySlot()
        }
    }

    companion object {
        const val RESPONSE_TIMEOUT_MS = 5000L
        const val WHITELIST_POLL_MS = 2000L
        const val WHITELIST_MAX_ATTEMPTS = 30
    }
}

private val AWAITING_ADD_KEY_RESULT =
    setOf(PairingPhase.CHECKING, PairingPhase.SENDING, PairingPhase.WAITING_FOR_CARD)

private fun occupiedSlots(slotMask: Int): List<Int> = (0 until Int.SIZE_BITS).filter { (slotMask ushr it) and 1 == 1 }

/** True when the car's key list holds [keyId]; the car lists longer hashes than the short ID we keep. */
private fun WhitelistInfo.lists(keyId: ByteArray): Boolean = whitelistEntries.any { it.publicKeySHA1.toByteArray().startsWithId(keyId) }

private fun ByteArray.startsWithId(keyId: ByteArray): Boolean = copyOf(keyId.size).contentEquals(keyId)

/**
 * True when the listing is whole: the car reported keys, listed an ID for
 * each occupied slot, and every ID is long enough to compare with ours.
 */
private fun WhitelistInfo.isComplete(keyIdSize: Int): Boolean =
    numberOfEntries > 0 &&
        whitelistEntries.size == numberOfEntries &&
        slotMask.countOneBits() == numberOfEntries &&
        whitelistEntries.all { it.publicKeySHA1.size >= keyIdSize }
