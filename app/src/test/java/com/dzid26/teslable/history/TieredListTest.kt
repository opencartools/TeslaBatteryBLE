// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import org.junit.Assert.assertEquals
import org.junit.Test

class TieredListTest {
    private fun list(maxRecent: Int) = TieredList<Long>(maxRecent, { it }, thin = { if (it % 2 == 0L) listOf(it) else emptyList() })

    @Test
    fun `the older part comes before the recent window`() {
        val tiers = list(maxRecent = 3)
        tiers.setRecent(listOf(10L, 11, 12, 13))
        assertEquals(11L, tiers.olderBefore)
        tiers.setOlder(listOf(1L, 2))
        assertEquals(listOf(1L, 2, 11, 12, 13), tiers.all)
    }

    @Test
    fun `an empty recent window takes every older sample`() {
        assertEquals(Long.MAX_VALUE, list(maxRecent = 3).olderBefore)
    }

    @Test
    fun `appends past the limit move the oldest recent samples to the older part, thinned`() {
        val tiers = list(maxRecent = 10)
        tiers.setRecent((0L until 10).toList())
        tiers.append((10L until 509).toList())
        assertEquals(509, tiers.all.size)

        tiers.append(listOf(509L))

        assertEquals((0L until 500 step 2).toList() + (500L until 510), tiers.all)
        assertEquals(500L, tiers.olderBefore)
    }
}
