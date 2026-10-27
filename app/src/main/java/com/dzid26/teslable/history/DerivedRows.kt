// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import com.dzid26.teslable.core.history.ActivityKind
import com.dzid26.teslable.core.history.ActivitySample
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.DriveSample
import com.dzid26.teslable.core.history.StatusSample
import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.ShiftStateKind

// The samples as `derived.db` rows and back. Enums are stored by name, so reordering one never changes a stored row;
// a name the app no longer knows reads back as null (or drops the row where the field is required).

internal fun BatterySample.toRow(): BatterySampleRow =
    BatterySampleRow(
        vehicleId = vehicleId,
        time = timestampMillis,
        level = batteryLevel,
        usableLevel = usableLevel,
        chargingState = chargingState?.name,
        chargeLimit = chargeLimit,
        ratedRangeMiles = ratedRangeMiles,
        chargeEnergyAdded = chargeEnergyAdded,
        chargeMilesAddedRated = chargeMilesAddedRated,
        chargerVoltage = chargerVoltage,
        chargeRateMph = chargeRateMph,
        readAt = readAtMillis,
    )

internal fun BatterySampleRow.toSample(): BatterySample =
    BatterySample(
        timestampMillis = time,
        batteryLevel = level,
        chargingState = chargingState?.let { name -> ChargingStateKind.entries.firstOrNull { it.name == name } },
        chargeLimit = chargeLimit,
        vehicleId = vehicleId,
        ratedRangeMiles = ratedRangeMiles,
        chargeEnergyAdded = chargeEnergyAdded,
        chargeMilesAddedRated = chargeMilesAddedRated,
        chargerVoltage = chargerVoltage,
        chargeRateMph = chargeRateMph,
        usableLevel = usableLevel,
        readAtMillis = readAt,
    )

internal fun DriveSample.toRow(): DriveSampleRow =
    DriveSampleRow(
        vehicleId = vehicleId,
        time = timestampMillis,
        shiftState = shiftState?.name,
        speed = speed,
        power = power,
        odometer = odometerInHundredthsOfAMile,
    )

internal fun DriveSampleRow.toSample(): DriveSample =
    DriveSample(
        timestampMillis = time,
        vehicleId = vehicleId,
        shiftState = shiftState?.let { name -> ShiftStateKind.entries.firstOrNull { it.name == name } },
        speed = speed,
        power = power,
        odometerInHundredthsOfAMile = odometer,
    )

internal fun StatusSample.toRow(): StatusSampleRow = StatusSampleRow(vehicleId, timestampMillis, asleep, userPresent, locked)

internal fun StatusSampleRow.toSample(): StatusSample = StatusSample(time, vehicleId, asleep, userPresent, locked)

internal fun ActivitySample.toRow(): ActivitySampleRow = ActivitySampleRow(vehicleId, kind.name, timestampMillis, on)

internal fun ActivitySampleRow.toSample(): ActivitySample? =
    ActivityKind.entries.firstOrNull { it.name == kind }?.let { ActivitySample(time, vehicleId, it, on) }
