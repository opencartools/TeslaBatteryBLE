// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

/**
 * A read model's samples in two parts (ADR-0014): the [recent] ones at full resolution, at most [maxRecent] of them
 * as before, and the [older] long-term history from `derived.db`, already thinned. Every older sample is before the
 * first recent one, so [all] is in time order when both parts are. When appends push the recent part past its limit,
 * its oldest samples go through [thin] into the older part instead of being dropped, so the history stays whole for
 * as long as the process runs.
 */
internal class TieredList<T>(
    private val maxRecent: Int,
    private val time: (T) -> Long,
    private val thin: (T) -> List<T> = { listOf(it) },
) {
    private var older: List<T> = emptyList()
    private var recent: List<T> = emptyList()

    /** The older part followed by the recent one, as a view: a new reading does not copy the whole history. */
    var all: List<T> = emptyList()
        private set

    /** Where the older part ends: the time of the first recent sample, or none while there is none. */
    val olderBefore: Long get() = recent.firstOrNull()?.let(time) ?: Long.MAX_VALUE

    /** Replaces the recent part, keeping its newest [maxRecent]. */
    fun setRecent(samples: List<T>) {
        recent = samples.takeLast(maxRecent)
        all = Joined(older, recent)
    }

    /** Replaces the older part with [samples], which must all be before [olderBefore]. */
    fun setOlder(samples: List<T>) {
        older = samples
        all = Joined(older, recent)
    }

    /** Appends [samples] to the recent part; any overflow moves to the older part, thinned. */
    fun append(samples: List<T>) {
        if (samples.isEmpty()) return
        recent = recent + samples
        val overflow = recent.size - maxRecent
        if (overflow >= SPILL) {
            older = older + recent.take(overflow).flatMap(thin)
            recent = recent.drop(overflow)
        }
        all = Joined(older, recent)
    }

    /** Two lists read as one, without a copy; both are never changed after they are built. */
    private class Joined<T>(
        private val first: List<T>,
        private val second: List<T>,
    ) : AbstractList<T>() {
        override val size: Int get() = first.size + second.size

        override fun get(index: Int): T = if (index < first.size) first[index] else second[index - first.size]
    }

    private companion object {
        /** The recent part may run this many samples over before they move, so a move is not paid on every append. */
        const val SPILL = 500
    }
}
