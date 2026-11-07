// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.R
import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.DriveSession
import com.dzid26.teslable.core.history.FullRangeScale
import com.dzid26.teslable.core.history.Session
import com.dzid26.teslable.core.history.Trip
import com.dzid26.teslable.core.history.navigation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** What the open row's figures are worked out from: the battery log, the fitted scale, the rated constant and the price. */
internal class SessionInputs(
    val samples: List<BatterySample>,
    val scale: FullRangeScale?,
    val ratedKwhPerMile: Float?,
    val pricePerKwh: Float?,
)

/** Which kinds the list shows. */
internal enum class SessionFilter { ALL, DRIVES, CHARGES }

/**
 * Every drive and charge in one list, newest first and grouped by day (`proposals/sessions-and-chart.md`, design A).
 * A row says what the session was (a drive takes its navigation's destination), when and how long, and its distance
 * or cost and levels; a charge's figures carry its Edit price button. A tap picks the row, which opens its figures in
 * place and zooms the history chart to it; a tap again lets go. The chart picks rows too (a tap on a band), so the
 * list opens far enough to show the pick. Shows nothing until there is a session. [sessions] and [trips] are oldest first.
 */
@Composable
internal fun SessionsCard(
    sessions: List<Session>,
    trips: List<Trip>,
    inputs: SessionInputs,
    selectedId: String?,
    onSelect: (Session?) -> Unit,
) {
    if (sessions.isEmpty()) return
    var filter by rememberSaveable { mutableStateOf(SessionFilter.ALL) }
    var shown by rememberSaveable { mutableIntStateOf(SESSIONS_PAGE) }
    val listed = remember(sessions, filter) { sessions.asReversed().filter { filter.keeps(it) } }
    val selectedIndex = listed.indexOfFirst { it.id == selectedId }
    LaunchedEffect(selectedIndex) { if (selectedIndex >= shown) shown = selectedIndex + 1 }
    val priced = rememberPricedCharges(sessions)
    val is24Hour = LocalIs24Hour.current ?: DateFormat.is24HourFormat(LocalContext.current)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.sessions_title_count, listed.size), style = MaterialTheme.typography.titleSmall)
            FilterChips(filter) { filter = it }
            var day: LocalDate? = null
            listed.take(shown).forEach { session ->
                val sessionDay = localDate(session.startMillis)
                if (sessionDay != day) {
                    day = sessionDay
                    DayHeader(sessionDay)
                }
                val trip = (session as? DriveSession)?.let { remember(it, trips) { it.navigation(trips) } }
                val row = SessionRowData(session, trip, priced[session.id], is24Hour)
                val selected = session.id == selectedId
                // The picked row and its figures share one tint, so the figures read as that row's.
                val tint = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                Column(Modifier.clip(RoundedCornerShape(8.dp)).background(tint)) {
                    SessionRow(row) { onSelect(if (selected) null else session) }
                    if (selected) SessionPanel(row, inputs)
                }
            }
            if (listed.size > shown) {
                TextButton(onClick = { shown += SESSIONS_PAGE }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.sessions_show_more))
                }
            }
        }
    }
}

private fun SessionFilter.keeps(session: Session): Boolean =
    when (this) {
        SessionFilter.ALL -> true
        SessionFilter.DRIVES -> session is DriveSession
        SessionFilter.CHARGES -> session is ChargeSession
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChips(
    filter: SessionFilter,
    onFilter: (SessionFilter) -> Unit,
) {
    // Three chips do not fit the narrowest phone in a large font; they wrap whole.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SessionFilter.entries.forEach { entry ->
            val label =
                when (entry) {
                    SessionFilter.ALL -> R.string.sessions_filter_all
                    SessionFilter.DRIVES -> R.string.sessions_filter_drives
                    SessionFilter.CHARGES -> R.string.sessions_filter_charges
                }
            FilterChip(selected = filter == entry, onClick = { onFilter(entry) }, label = { Text(stringResource(label)) })
        }
    }
}

/** "Thu, Oct 9": the day the sessions under it started. */
@Composable
private fun DayHeader(day: LocalDate) {
    val pattern = remember { DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd") }
    Text(
        text = day.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault())),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** One row's inputs: the session, its navigation (drives only), its price (charges only) and the clock format. */
internal class SessionRowData(
    val session: Session,
    val trip: Trip?,
    val priced: PricedSession?,
    val is24Hour: Boolean,
)

/**
 * A dot in the session's chart colour, its name and start time with its length, and at the end its distance (a drive)
 * or its cost or energy (a charge) over the levels it went between.
 */
@Composable
private fun SessionRow(
    row: SessionRowData,
    onClick: () -> Unit,
) {
    val session = row.session
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (session is DriveSession) driveChartColor else chargingChartColor),
        )
        Column(Modifier.weight(1f)) {
            Text(sessionName(row), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                sessionWhen(row),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            sessionAmount(row)?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 1) }
            levelsText(session)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** The navigation's destination for a drive that had one, else "Drive" or "Charge". */
@Composable
private fun sessionName(row: SessionRowData): String =
    when (row.session) {
        is DriveSession -> row.trip?.destination ?: stringResource(R.string.session_drive)
        else -> stringResource(R.string.session_charge)
    }

/** "2:05 PM · 42min", or "2:05 PM · charging" for the session still going. */
@Composable
private fun sessionWhen(row: SessionRowData): String {
    val session = row.session
    val start = formatTime(session.startMillis, showDate = false, is24Hour = row.is24Hour)
    val length =
        when {
            session.inProgress && session is DriveSession -> stringResource(R.string.session_driving)
            session.inProgress -> stringResource(R.string.session_charging)
            else -> tripTimeText(session.endMillis - session.startMillis)
        }
    return listOfNotNull(start, length).joinToString(" · ")
}

/** A drive's distance, a charge's cost (or its energy without a price); null when neither is known. */
@Composable
private fun sessionAmount(row: SessionRowData): String? =
    when (val session = row.session) {
        is DriveSession -> tripDistanceText(session.distanceMiles.toFloat())
        else ->
            row.priced?.cost?.let { formatMoney(it, row.priced.currencyCode ?: LocalCurrencyCode.current) }
                ?: session.energyKwh?.let { "+${formatKwh(it)}kWh" }
    }

/** "80% to 72%", or null without both levels. */
@Composable
internal fun levelsText(session: Session): String? {
    val start = session.socStartPercent
    val end = session.socEndPercent
    return if (start == null || end == null) null else stringResource(R.string.session_levels, start.roundToInt(), end.roundToInt())
}

private fun localDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

/** Rows shown at first, and added by each "Show more". */
private const val SESSIONS_PAGE = 8
