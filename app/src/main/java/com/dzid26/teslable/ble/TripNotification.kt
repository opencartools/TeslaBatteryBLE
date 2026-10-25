// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.dzid26.teslable.R
import com.dzid26.teslable.UnitStore
import com.dzid26.teslable.core.health.tripEstimate
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.FullRangeScale
import com.dzid26.teslable.core.history.RouteSample
import com.dzid26.teslable.core.history.activeRoute
import com.dzid26.teslable.core.history.compactDuration
import com.dzid26.teslable.core.history.trafficMinutes
import com.dzid26.teslable.core.history.tripLowFrom
import com.dzid26.teslable.core.history.tripProgress
import com.dzid26.teslable.core.units.DistanceUnit
import com.dzid26.teslable.history.FullRangeScales
import java.text.DecimalFormatSymbols
import kotlin.math.roundToInt

/**
 * What the notification says about the trip the car is navigating (#207): the same numbers as the trip card,
 * rounded to the step the text shows, so two notices are equal exactly when their text is, and the service
 * re-posts only when something visible changes. The destination is left out: the shade shows on a lock screen.
 */
internal data class TripNotice(
    val milesLeftTenths: Int?,
    val minutesLeft: Int?,
    val trafficMinutes: Int?,
    val arrivalPercent: Int?,
    val arrivalRatedMiles: Int?,
    /** How far along the trip is, in whole percent, for the progress bar. */
    val progressPercent: Int? = null,
    /** Where along the whole trip, in whole percent, the predicted level falls under the reserve; null when it does not. */
    val lowFromPercent: Int? = null,
)

/** The route-independent inputs of [tripEstimate] for one car, fitted from its history. */
internal data class TripInputs(
    val currentRatedMiles: Float?,
    val scale: FullRangeScale?,
    val currentPercent: Float? = null,
)

/** Fits [TripInputs] from one car's [samples]; the work the service caches while the history is unchanged. */
internal fun tripInputs(samples: List<BatterySample>): TripInputs =
    TripInputs(
        currentRatedMiles = samples.lastOrNull()?.ratedRangeMiles,
        scale = FullRangeScales.of(samples),
        currentPercent = samples.lastOrNull()?.batteryLevel?.toFloat(),
    )

/** The notice for a live [route], or null when it says nothing the shade could show. */
internal fun tripNotice(
    route: RouteSample,
    inputs: TripInputs,
): TripNotice? {
    val estimate = tripEstimate(route, inputs.currentRatedMiles, inputs.scale, ratedKwhPerMile = null, pricePerKwh = null)
    val notice =
        TripNotice(
            milesLeftTenths = estimate.milesRemaining?.let { (it * TENTHS).roundToInt() },
            minutesLeft = route.minutesToArrival?.takeIf { it.isFinite() && it >= 0f }?.roundToInt(),
            trafficMinutes = route.trafficMinutes(),
            arrivalPercent = estimate.arrivalPercent?.roundToInt(),
            arrivalRatedMiles = estimate.arrivalRatedMiles?.roundToInt(),
            progressPercent = route.tripProgress()?.let { (it * PERCENT_FULL).roundToInt() },
            lowFromPercent = route.tripLowFrom(inputs.currentPercent)?.let { (it * PERCENT_FULL).roundToInt() },
        )
    return notice.takeIf { it.milesLeftTenths != null || it.minutesLeft != null || it.arrivalPercent != null }
}

/**
 * Builds the [TripNotice] of a car from the newest route of each car. The history fit behind it is heavy, so it
 * is redone only when the history list changed, and dropped when the route ends.
 */
internal class TripNoticeSource {
    private val cache = mutableMapOf<String, Pair<List<BatterySample>, TripInputs>>()

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
 * progress notification (a Live Update when the user allows them): the car on a bar that turns red where the
 * car predicts the battery falls under the reserve, and the minutes left in the status bar chip. Otherwise the
 * collapsed line leads with the level and the car's predicted arrival level (or the distance and time left
 * without one), and the expanded text adds the usual state line, then the distance and time left after a bold
 * label and the arrival with its range. Adds to the state text, never replaces it.
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
        val line = listOfNotNull(percent?.let { "$it%" }, text.arrivalShort(notice), remaining).joinToString(" \u00B7 ")
        return setContentText(line)
            .setStyle(progressStyle(text.context, notice, progress))
            .setShortCriticalText(text.shortRemaining(notice))
            .setRequestPromotedOngoing(true)
    }
    val body =
        SpannableStringBuilder(stateText).apply {
            if (remaining != null) {
                appendLine()
                appendBold(text.label(), remaining)
            }
            text.arrival(notice)?.let { appendLine().append(it) }
        }
    return setContentText(headline).setStyle(NotificationCompat.BigTextStyle().bigText(body))
}

/** The bar: [TRIP_BAR_MAX] units for the whole trip, green up to the predicted low stretch and red from there. */
private fun progressStyle(
    context: Context,
    notice: TripNotice,
    progress: Int,
): NotificationCompat.ProgressStyle {
    val lowFrom = notice.lowFromPercent?.coerceIn(0, TRIP_BAR_MAX)
    val style =
        NotificationCompat
            .ProgressStyle()
            .setProgress(progress)
            .setProgressTrackerIcon(IconCompat.createWithResource(context, R.drawable.ic_driving_tracker))
    if (lowFrom == null || lowFrom >= TRIP_BAR_MAX) {
        return style.addProgressSegment(NotificationCompat.ProgressStyle.Segment(TRIP_BAR_MAX).setColor(COLOR_OK))
    }
    if (lowFrom > 0) {
        style.addProgressSegment(NotificationCompat.ProgressStyle.Segment(lowFrom).setColor(COLOR_OK))
    }
    return style
        .addProgressSegment(NotificationCompat.ProgressStyle.Segment(TRIP_BAR_MAX - lowFrom).setColor(COLOR_LOW))
        .addProgressPoint(NotificationCompat.ProgressStyle.Point(lowFrom).setColor(COLOR_LOW))
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

    /** "Trip: ", bold before the distance and time left in the expanded text. */
    fun label(): String = context.getString(R.string.notification_trip_label)

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

private const val TENTHS = 10f
private const val PERCENT_FULL = 100f
private const val TRIP_BAR_MAX = 100
private val COLOR_OK = 0xFF2E9D4F.toInt()
private val COLOR_LOW = 0xFFD93025.toInt()
private const val MILLIS_PER_MINUTE = 60_000L
