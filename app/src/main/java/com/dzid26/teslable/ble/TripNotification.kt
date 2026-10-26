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
import com.dzid26.teslable.BuildConfig
import com.dzid26.teslable.R
import com.dzid26.teslable.UnitStore
import com.dzid26.teslable.core.health.tripEstimate
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.FullRangeScale
import com.dzid26.teslable.core.history.RouteSample
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

/** The bar: [TRIP_BAR_MAX] units for the whole trip, in the system's default color. */
private fun progressStyle(
    context: Context,
    progress: Int,
): NotificationCompat.ProgressStyle =
    NotificationCompat
        .ProgressStyle()
        .setProgress(progress)
        .setProgressTrackerIcon(IconCompat.createWithResource(context, R.drawable.ic_driving_tracker))
        .addProgressSegment(NotificationCompat.ProgressStyle.Segment(TRIP_BAR_MAX))

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
private const val TRIP_BAR_MAX = 100
private const val MILLIS_PER_MINUTE = 60_000L
