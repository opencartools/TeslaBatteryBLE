// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.protocol.TeslaNames

/**
 * One known Tesla.
 *
 * [bleName] is the advertised name (`S<sha1(VIN)[:8]>C`): stable for the life
 * of the car and the identity every other record hangs off. The BLE [address]
 * is mutable — Android may see the car at a new address — and is only used to
 * connect.
 */
data class Vehicle(
    val bleName: String,
    val address: String,
    val gattName: String? = null,
    val displayName: String? = null,
    val vin: String? = null,
    val keySlot: Int? = null,
    /** The rated range in miles when the car was new, the baseline for state of health; set by the owner. */
    val factoryRangeMiles: Float? = null,
    val lastSeenMillis: Long = 0L,
) {
    /** What the UI calls this car: user-set name, then the car's own name. */
    val title: String get() = displayName?.takeIf { it.isNotBlank() } ?: gattName ?: bleName

    /**
     * True when the stored [vin] can personalise a BLE session: the session-info HMAC takes exactly
     * [VIN_LENGTH] characters. A saved VIN already matched the advertised name, so the length is the
     * remaining question; whether the owner typed one at all is a prompt (see `vinRequestFor`).
     */
    val hasSessionVin: Boolean get() = vin?.let { normalizeVin(it).length } == VIN_LENGTH

    /** True when [candidate] is the VIN that hashes to this car's advertised name. */
    fun acceptsVin(candidate: String): Boolean {
        val normalized = normalizeVin(candidate)
        return normalized.length == VIN_LENGTH &&
            runCatching { TeslaNames.bleName(normalized) == bleName }.getOrDefault(false)
    }

    /**
     * How [input] reads against this car's advertised name, for the edit dialog and the VIN prompt: no
     * two screens may decide differently whether a typed VIN is the right one.
     */
    fun vinVerdict(input: String): VinVerdict {
        val normalized = normalizeVin(input)
        return when {
            normalized.isEmpty() -> VinVerdict.EMPTY
            normalized.length != VIN_LENGTH -> VinVerdict.PARTIAL
            acceptsVin(normalized) -> VinVerdict.MATCH
            else -> VinVerdict.MISMATCH
        }
    }

    /** True once the app's key is enrolled in this car's whitelist. */
    val isPaired: Boolean get() = keySlot != null

    companion object {
        const val VIN_LENGTH = 17

        fun normalizeVin(raw: String): String = raw.trim().uppercase()
    }
}

/** What the owner can say about a car in the edit dialog; a null field clears or leaves that setting unset. */
data class CarDetails(
    val vin: String?,
    val factoryRangeMiles: Float?,
)

/**
 * How a typed VIN reads against a car's advertised name. The edit dialog and the VIN prompt render the
 * same verdict, so a VIN accepted in one is accepted in the other.
 */
enum class VinVerdict {
    /** Nothing typed: the dialog keeps the stored VIN. */
    EMPTY,

    /** Not [Vehicle.VIN_LENGTH] characters yet: no verdict on it. */
    PARTIAL,

    /** The VIN hashes to the car's advertised name. */
    MATCH,

    /** A full-length VIN that hashes to another name. */
    MISMATCH,
}
