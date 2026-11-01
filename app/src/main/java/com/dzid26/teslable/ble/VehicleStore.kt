// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists known vehicles (identity, current address, per-vehicle VIN and key
 * slot).
 */
class VehicleStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The stored vehicles. A list that cannot be parsed is read as empty, and
     * its raw text is first kept under a separate key, so the next [save]
     * cannot destroy the only copy of the VINs and key slots.
     */
    fun load(): List<Vehicle> {
        val raw = prefs.getString(KEY_VEHICLES, null) ?: return emptyList()
        val vehicles = parse(raw)
        if (vehicles == null) {
            // commit(), not apply(): the copy is on disk before any save()
            // replaces the list.
            val kept = prefs.edit().putString(KEY_VEHICLES_UNREADABLE, raw).commit()
            // Only the fact goes to the log: the list holds VINs, and the
            // parser's message would quote it.
            Log.w(LOG_TAG, "Stored vehicle list is unreadable; starting empty (copy kept: $kept)")
            return emptyList()
        }
        return vehicles
    }

    fun save(vehicles: Collection<Vehicle>) {
        val array = JSONArray()
        for (vehicle in vehicles.sortedBy { it.bleName }) {
            array.put(
                JSONObject().apply {
                    put(KEY_BLE_NAME, vehicle.bleName)
                    put(KEY_ADDRESS, vehicle.address)
                    vehicle.gattName?.let { put(KEY_GATT_NAME, it) }
                    vehicle.displayName?.let { put(KEY_DISPLAY_NAME, it) }
                    vehicle.vin?.let { put(KEY_VIN, it) }
                    vehicle.keySlot?.let { put(KEY_KEY_SLOT, it) }
                    vehicle.factoryRangeMiles?.let { put(KEY_FACTORY_RANGE, it.toDouble()) }
                    if (vehicle.lastSeenMillis > 0) put(KEY_LAST_SEEN, vehicle.lastSeenMillis)
                },
            )
        }
        prefs.edit().putString(KEY_VEHICLES, array.toString()).apply()
    }

    /** The vehicles in [raw], or null when it is not a valid list. */
    private fun parse(raw: String): List<Vehicle>? =
        runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val bleName = item.optString(KEY_BLE_NAME).takeIf { it.isNotEmpty() } ?: continue
                    // An empty address is a car whose cached Bluetooth address was forgotten;
                    // it stays in the list and a scan fills the address in again.
                    val address = item.optString(KEY_ADDRESS)
                    add(
                        Vehicle(
                            bleName = bleName,
                            address = address,
                            gattName = item.optString(KEY_GATT_NAME).takeIf { it.isNotEmpty() },
                            displayName = item.optString(KEY_DISPLAY_NAME).takeIf { it.isNotEmpty() },
                            vin = item.optString(KEY_VIN).takeIf { it.isNotEmpty() },
                            keySlot = item.optInt(KEY_KEY_SLOT, -1).takeIf { it >= 0 },
                            factoryRangeMiles = item.optDouble(KEY_FACTORY_RANGE).takeIf { it.isFinite() && it > 0.0 }?.toFloat(),
                            lastSeenMillis = item.optLong(KEY_LAST_SEEN, 0L),
                        ),
                    )
                }
            }
        }.getOrNull()

    private companion object {
        const val LOG_TAG = "VehicleStore"
        const val PREFS = "vehicles"
        const val KEY_VEHICLES = "vehicles"

        /** The last stored list that failed to parse, kept for recovery. */
        const val KEY_VEHICLES_UNREADABLE = "vehicles_unreadable"
        const val KEY_BLE_NAME = "bleName"
        const val KEY_ADDRESS = "address"
        const val KEY_GATT_NAME = "gattName"
        const val KEY_DISPLAY_NAME = "displayName"
        const val KEY_VIN = "vin"
        const val KEY_KEY_SLOT = "keySlot"
        const val KEY_FACTORY_RANGE = "factoryRangeMiles"
        const val KEY_LAST_SEEN = "lastSeen"
    }
}
