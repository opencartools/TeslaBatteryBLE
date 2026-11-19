// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import com.dzid26.teslable.core.history.HistoryRange

/** The narrowest view a history range allows: the fiftieth of the chart, or an hour where that is narrower. */
internal fun HistoryRange.minViewSpan(): Double =
    durationMillis?.let { (MILLIS_PER_HOUR / it).coerceAtMost(MIN_VIEW_SPAN) } ?: MIN_VIEW_SPAN

private const val MILLIS_PER_HOUR = 3_600_000.0
