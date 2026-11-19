// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * A stretch of time with a meaning the history chart shades: a power-down gap, a sleep, a climate run.
 * [startMillis] and [endMillis] are the readings at its ends, so the span covers exactly what was measured.
 */
data class TimeSpan(
    val startMillis: Long,
    val endMillis: Long,
)
