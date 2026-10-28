// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.dzid26.teslable.BuildConfig
import com.dzid26.teslable.R
import com.dzid26.teslable.UnitStore
import com.dzid26.teslable.core.health.tripEstimate
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.FullRangeScale
import com.dzid26.teslable.core.history.RouteSample
import com.dzid26.teslable.core.history.TRIP_END_GAP_MILLIS
import com.dzid26.teslable.core.history.activeRoute
import com.dzid26.teslable.core.history.compactDuration
import com.dzid26.teslable.core.history.trafficMinutes
import com.dzid26.teslable.core.history.tripProgress
import com.dzid26.teslable.core.units.DistanceUnit
import com.dzid26.teslable.history.FullRangeScales
import java.text.DecimalFormatSymbols
import kotlin.math.roundToInt

/**
 * What the notification says about the trip the car is navigating (#207): the same numbers as the trip card,
 * rounded to the step the text shows, so two notices are equal exactly when their text is, and the service
 * re-posts only when something visible changes.
 */
internal data class TripNotice(
    val destination: String?,
    val milesLeftTenths: Int?,
    val minutesLeft: Int?,
    val trafficMinutes: Int?,
    val arrivalPercent: Int?,
    val arrivalRatedMiles: Int?,
    /** How far along the trip is, in whole percent, for the progress bar. */
    val progressPercent: Int? = null,
)

/**
 * Decides which trip notification pops up. A car's trip is identified by its destination and alerts once. The trip
 * ends only once its notice has been seen missing for [TRIP_END_GAP_MILLIS], so a dropped link, a lapsed route
 * sample, a car-count change or a stretch with no calls at all keeps the trip; the next trip, even to the same
 * place, then alerts again.
 */
internal class TripAlerts {
    private class Seen(
        val key: String,
        var goneSinceMillis: Long? = null,
    )

    private val seen = mutableMapOf<String, Seen>()

    fun shouldAlert(
        bleName: String,
        notice: TripNotice?,
        nowMillis: Long,
    ): Boolean {
        val previous = seen[bleName]
        val goneSince = previous?.goneSinceMillis
        if (notice == null) {
            when {
                previous == null -> Unit
                goneSince == null -> previous.goneSinceMillis = nowMillis
                nowMillis - goneSince > TRIP_END_GAP_MILLIS -> seen.remove(bleName)
            }
            return false
        }
        val key = notice.destination.orEmpty()
        val sameTrip = previous != null && previous.key == key && (goneSince == null || nowMillis - goneSince <= TRIP_END_GAP_MILLIS)
        if (sameTrip) {
            previous.goneSinceMillis = null
        } else {
            seen[bleName] = Seen(key)
        }
        return !sameTrip
    }
}

/** The route-independent inputs of [tripEstimate] for one car, fitted from its history. */
internal data class TripInputs(
    val currentRatedMiles: Float?,
    val scale: FullRangeScale?,
)

/** Fits [TripInputs] from one car's [samples]; the work the service caches while the history is unchanged. */
internal fun tripInputs(samples: List<BatterySample>): TripInputs =
    FullRangeScales.of(samples).let { scale ->
        TripInputs(
            currentRatedMiles = samples.lastOrNull()?.ratedRangeMiles,
            scale = scale,
        )
    }

/** The notice for a live [route], or null when it says nothing the shade could show. */
internal fun tripNotice(
    route: RouteSample,
    inputs: TripInputs,
): TripNotice? {
    val estimate = tripEstimate(route, inputs.currentRatedMiles, inputs.scale, ratedKwhPerMile = null, pricePerKwh = null)
    val notice =
        TripNotice(
            destination = route.destination?.takeIf { it.isNotBlank() },
            milesLeftTenths = estimate.milesRemaining?.let { (it * TENTHS).roundToInt() },
            minutesLeft = route.minutesToArrival?.takeIf { it.isFinite() && it >= 0f }?.roundToInt(),
            trafficMinutes = route.trafficMinutes(),
            arrivalPercent = estimate.arrivalPercent?.roundToInt(),
            arrivalRatedMiles = estimate.arrivalRatedMiles?.roundToInt(),
            progressPercent = route.tripProgress()?.let { (it * PERCENT_FULL).roundToInt() },
        )
    return notice.takeIf { it.milesLeftTenths != null || it.minutesLeft != null || it.arrivalPercent != null }
}

/**
 * Builds the [TripNotice] of a car from the newest route of each car. The history fit behind it is heavy, so it
 * is redone only when the history list changed, and dropped when the route ends.
 */
internal class TripNoticeSource {
    private val cache = mutableMapOf<String, Pair<List<BatterySample>, TripInputs>>()

    /** Synchronized: the service builds its models off the main thread, from the state flow and its minute tick. */
    @Synchronized
    fun noticeFor(
        bleName: String,
        history: List<BatterySample>,
        routes: Map<String, RouteSample>,
        nowMillis: Long,
    ): TripNotice? {
        val route = activeRoute(routes[bleName], nowMillis)
        if (route == null) {
            cache.remove(bleName)
            return null
        }
        val inputs =
            cache[bleName]?.takeIf { it.first === history }?.second
                ?: tripInputs(history.filter { it.vehicleId == bleName }).also { cache[bleName] = history to it }
        return tripNotice(route, inputs)
    }
}

/**
 * Shows the trip on a car's ongoing notification. On Android 16 and later, with the trip's length known, it is a
 * progress notification (a Live Update when the user allows them): the arrow on a bar, and the minutes left in the
 * status bar chip; the state line moves to the sub-text. Otherwise the collapsed line leads with the level and the
 * car's predicted arrival level (or the distance and time left without one), and the expanded text adds the usual
 * state line, then the distance and time left after a bold label and the arrival with its range. Adds to the state text, never replaces it.
 */
internal fun NotificationCompat.Builder.showTrip(
    text: TripTextContext,
    notice: TripNotice,
    stateText: CharSequence,
    percent: Int?,
): NotificationCompat.Builder {
    val remaining = text.remaining(notice)
    val headline =
        listOfNotNull(percent?.let { "$it%" }, text.arrivalShort(notice) ?: remaining)
            .joinToString(" \u00B7 ")
            .ifEmpty { stateText.toString() }
    val progress = notice.progressPercent
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && progress != null) {
        val line =
            listOfNotNull(
                notice.destination?.ellipsize(),
                percent?.let { "$it%" },
                text.arrivalShort(notice),
                remaining,
            ).joinToString(" \u00B7 ")
        // The progress style shows only the content text, so the state line moves to the sub-text (with a PR build's label).
        val subText = if (BuildConfig.BUILD_LABEL.isEmpty()) stateText else "${BuildConfig.BUILD_LABEL} \u00B7 $stateText"
        return setContentText(line)
            .setSubText(subText)
            .setStyle(progressStyle(text.context, progress))
            .setShortCriticalText(text.shortRemaining(notice))
            .setRequestPromotedOngoing(true)
    }
    val body =
        SpannableStringBuilder(stateText).apply {
            if (remaining != null) {
                appendLine()
                appendBold(text.label(notice), remaining)
            }
            text.arrival(notice)?.let { appendLine().append(it) }
        }
    return setContentText(headline).setStyle(NotificationCompat.BigTextStyle().bigText(body))
}

/**
 * The bar: [TRIP_BAR_MAX] units for the whole trip. The part already driven is dimmed on the left and the part still
 * to go is in the accent color on the right; the arrow sits where the car is, on the boundary between them (see
 * [drivenUnits]). Styling by progress is off, or the platform would fade the part still to go.
 */
private fun progressStyle(
    context: Context,
    progress: Int,
): NotificationCompat.ProgressStyle {
    val percent = progress.coerceIn(0, PERCENT_FULL.toInt())
    val driven = drivenUnits(percent, context.resources.configuration.screenWidthDp - NOTIFICATION_BAR_INSETS_DP)
    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val dimmed = context.getColor(if (night) android.R.color.system_neutral1_600 else android.R.color.system_neutral1_400)
    val accent = context.getColor(if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
    val style =
        NotificationCompat
            .ProgressStyle()
            .setProgress(percent * TRIP_BAR_MAX / PERCENT_FULL.toInt())
            .setStyledByProgress(false)
            .setProgressTrackerIcon(IconCompat.createWithResource(context, R.drawable.ic_driving_tracker))
    if (driven > 0) {
        style.addProgressSegment(NotificationCompat.ProgressStyle.Segment(driven).setColor(dimmed))
    }
    if (driven < TRIP_BAR_MAX) {
        style.addProgressSegment(NotificationCompat.ProgressStyle.Segment(TRIP_BAR_MAX - driven).setColor(accent))
    }
    return style
}

/**
 * The driven segment's length in [TRIP_BAR_MAX] units for a trip [percent] done, on a bar [barWidthDp] wide.
 * The platform's segments span the whole bar, but its tracker icon slides only between the bar's ends, its left edge
 * going from 0 to the bar width less the icon's own: so the icon's center is the progress point only at 50%, and half
 * an icon to the right of it at the start (NotificationProgressBar.setTrackerPos). The boundary moves to where the
 * icon's center lands instead, so the arrow sits on it all along the trip.
 */
internal fun drivenUnits(
    percent: Int,
    barWidthDp: Int,
): Int {
    val done = percent.coerceIn(0, PERCENT_FULL.toInt()) / PERCENT_FULL
    val iconShare = (TRACKER_WIDTH_DP / barWidthDp.coerceAtLeast(MIN_BAR_WIDTH_DP)).coerceAtMost(1f)
    val boundary = done + (HALF - done) * iconShare
    return (boundary * TRIP_BAR_MAX).roundToInt().coerceIn(0, TRIP_BAR_MAX)
}

private fun SpannableStringBuilder.appendBold(
    label: String,
    text: String,
) {
    val start = length
    append(label)
    setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    append(text)
}

/** The phone's settings that shape the trip text: read when a notification is built, so a change shows on the next post. */
internal class TripTextContext(
    val context: Context,
) {
    val unit: DistanceUnit = UnitStore(context).preference().resolve()
    private val separator = DecimalFormatSymbols.getInstance().decimalSeparator

    /** "Trip to Home: " (or "Trip: " without a destination), bold before the distance and time left in the expanded text. */
    fun label(notice: TripNotice): String =
        notice.destination?.let { context.getString(R.string.notification_trip_label_to, it) }
            ?: context.getString(R.string.notification_trip_label)

    /** "4.7 mi · 12m left (+3m traffic)", with whichever parts the car sent. */
    fun remaining(notice: TripNotice): String? {
        val distance = notice.milesLeftTenths?.let { unit.format(it / TENTHS, decimals = 1, decimalSeparator = separator) }
        val time =
            notice.minutesLeft?.let { compactDuration(it * MILLIS_PER_MINUTE) ?: context.getString(R.string.trip_under_a_minute) }
        val traffic =
            notice.trafficMinutes
                ?.let { compactDuration(it * MILLIS_PER_MINUTE) }
                ?.let { context.getString(R.string.notification_trip_traffic, it) }
        val parts = listOfNotNull(distance, time).takeIf { it.isNotEmpty() } ?: return null
        val left = context.getString(R.string.notification_trip_left, parts.joinToString(" · "))
        return if (traffic == null) left else "$left $traffic"
    }

    /** "12min", short enough for the status bar chip of a Live Update. */
    fun shortRemaining(notice: TripNotice): String? =
        notice.minutesLeft?.let { compactDuration(it * MILLIS_PER_MINUTE) ?: context.getString(R.string.trip_under_a_minute) }

    /** "Arrive with 44%", for the collapsed line. */
    fun arrivalShort(notice: TripNotice): String? = notice.arrivalPercent?.let { context.getString(R.string.trip_arrival, it) }

    /** "Arrive with 44% (about 118 mi)". */
    fun arrival(notice: TripNotice): String? {
        val percent = notice.arrivalPercent ?: return null
        val ratedMiles = notice.arrivalRatedMiles
        return if (ratedMiles == null) {
            context.getString(R.string.trip_arrival, percent)
        } else {
            context.getString(R.string.trip_arrival_range, percent, unit.format(ratedMiles.toFloat()))
        }
    }
}

/** A destination for the one-line text: a long name must not push the arrival and the time left off the end. */
private fun String.ellipsize(): String = if (length > MAX_DESTINATION_CHARS) take(MAX_DESTINATION_CHARS - 1).trimEnd() + "\u2026" else this

private const val MAX_DESTINATION_CHARS = 18
private const val TENTHS = 10f
private const val PERCENT_FULL = 100f

/** Units of the notification's trip bar: fine enough that the boundary can sit under the arrow to well under 1dp. */
private const val TRIP_BAR_MAX = 1000

/** The platform's tracker icon height (notification_progress_tracker_height); the square arrow icon is as wide. */
private const val TRACKER_WIDTH_DP = 20f

/**
 * What the shade takes off the screen width before the bar: the notification's side margins (16dp each), its
 * content margins (16dp each) and the bar's own margins (2dp each). Close enough on phones; the boundary only moves
 * by a fraction of the icon width.
 */
private const val NOTIFICATION_BAR_INSETS_DP = 68
private const val MIN_BAR_WIDTH_DP = 100
private const val HALF = 0.5f
private const val MILLIS_PER_MINUTE = 60_000L
