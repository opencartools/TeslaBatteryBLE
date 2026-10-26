// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import com.dzid26.teslable.core.units.EfficiencyUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class EfficiencyCycleTest {
    @Test
    fun `next visits every form and wraps around`() {
        val order = generateSequence(EfficiencyUnit.ENERGY_PER_DISTANCE) { it.next() }.take(EfficiencyUnit.entries.size + 1).toList()
        assertEquals(
            listOf(
                EfficiencyUnit.ENERGY_PER_DISTANCE,
                EfficiencyUnit.ENERGY_PER_100_DISTANCE,
                EfficiencyUnit.DISTANCE_PER_ENERGY,
                EfficiencyUnit.ENERGY_PER_DISTANCE,
            ),
            order,
        )
    }
}
