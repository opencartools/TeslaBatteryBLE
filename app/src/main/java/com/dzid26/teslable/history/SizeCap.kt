// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

/**
 * The safety cap on a table's size (ADR-0014): while its series add up to more than [cap] bytes, the
 * oldest rows of the largest series go (through [deleteOldest]), down to [TARGET_PERCENT] of the cap.
 * A row counts as its blob plus [ROW_OVERHEAD_BYTES] of keys and indexes (190 measured on real logs).
 * Callers run it in a transaction.
 */
internal fun trimToCap(
    cap: Long,
    sizes: () -> List<SeriesSize>,
    deleteOldest: (seriesId: Long, count: Int) -> Int,
) {
    val target = cap / PERCENT * TARGET_PERCENT
    var current = sizes()
    var total = current.sumOf { it.bytes() }
    while (total > cap) {
        val largest = current.maxBy { it.bytes() }
        val perRow = largest.bytes() / largest.rowCount
        deleteOldest(largest.seriesId, ((total - target) / perRow + 1).coerceAtMost(largest.rowCount).toInt())
        current = sizes()
        total = current.sumOf { it.bytes() }
    }
}

private fun SeriesSize.bytes(): Long = blobBytes + rowCount * ROW_OVERHEAD_BYTES

/** What a row costs on disk besides its blob: the key columns and the indexes. */
private const val ROW_OVERHEAD_BYTES = 200L
private const val PERCENT = 100
private const val TARGET_PERCENT = 90
