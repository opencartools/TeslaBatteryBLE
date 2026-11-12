// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.squareup.wire.Instant
import com.tesla.generated.vcsec.VehicleStatus

/**
 * Whether a VCSEC status reading goes into the vehicle's status log
 * (ADR-0008). A reading is logged when it is the first for the vehicle
 * ([last] is null), the first since the link (re)connected, or when the raw
 * status differs from [last]. An unchanged status is logged again once
 * [STATUS_HEARTBEAT_MILLIS] has passed since [last]: that heartbeat tells a
 * sleeping car with a phone connected apart from a phone out of range, and a
 * gap longer than it while the link is up means the car stopped answering.
 * A reading timed before [last] (the phone's clock moved back) is logged too,
 * since the time that passed is unknown.
 *
 * @param last the newest record logged for this vehicle, or null when there is none.
 * @param deviceTimestamp the phone's clock when [status] arrived.
 * @param firstAfterConnect true for the first reading since the link became ready.
 */
fun shouldLogStatus(
    last: BleRecord?,
    status: VehicleStatus,
    deviceTimestamp: Instant,
    firstAfterConnect: Boolean,
): Boolean {
    if (last == null || firstAfterConnect || last.vehicle_status != status) return true
    val lastAt = last.device_timestamp?.toEpochMilli() ?: return true
    val elapsed = deviceTimestamp.toEpochMilli() - lastAt
    return elapsed < 0 || elapsed >= STATUS_HEARTBEAT_MILLIS
}

/** An unchanged status is logged again after this long, so the log bounds when the car was last heard. */
internal const val STATUS_HEARTBEAT_MILLIS = 60_000L
