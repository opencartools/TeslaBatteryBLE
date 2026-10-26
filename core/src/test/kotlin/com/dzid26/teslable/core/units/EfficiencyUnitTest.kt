// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.units

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EfficiencyUnitTest {
    // 0.2736 kWh per rated mile: 273.6 Wh/mi, 170.0 Wh/km.
    private val rate = 0.2736f

    @Test
    fun `energy per distance reads in Wh`() {
        assertEquals("274Wh/\u2060mi", EfficiencyUnit.ENERGY_PER_DISTANCE.format(rate, DistanceUnit.MILES))
        assertEquals("170Wh/\u2060km", EfficiencyUnit.ENERGY_PER_DISTANCE.format(rate, DistanceUnit.KILOMETERS))
    }

    @Test
    fun `energy per 100 distance reads like the labels`() {
        assertEquals("27.4kWh/\u2060100mi", EfficiencyUnit.ENERGY_PER_100_DISTANCE.format(rate, DistanceUnit.MILES))
        assertEquals("17.0kWh/\u2060100km", EfficiencyUnit.ENERGY_PER_100_DISTANCE.format(rate, DistanceUnit.KILOMETERS))
    }

    @Test
    fun `distance per energy is the inverse`() {
        assertEquals("3.65mi/\u2060kWh", EfficiencyUnit.DISTANCE_PER_ENERGY.format(rate, DistanceUnit.MILES))
        assertEquals("5.88km/\u2060kWh", EfficiencyUnit.DISTANCE_PER_ENERGY.format(rate, DistanceUnit.KILOMETERS))
    }

    @Test
    fun `the decimal separator and zero padding follow the caller`() {
        assertEquals("3,65mi/\u2060kWh", EfficiencyUnit.DISTANCE_PER_ENERGY.format(rate, DistanceUnit.MILES, ','))
        assertEquals("5.05mi/\u2060kWh", EfficiencyUnit.DISTANCE_PER_ENERGY.format(0.198f, DistanceUnit.MILES))
    }

    @Test
    fun `a missing or non positive rate shows nothing`() {
        EfficiencyUnit.entries.forEach { unit ->
            assertNull(unit.format(0f, DistanceUnit.MILES))
            assertNull(unit.format(-0.1f, DistanceUnit.MILES))
            assertNull(unit.format(Float.NaN, DistanceUnit.MILES))
        }
    }
}
