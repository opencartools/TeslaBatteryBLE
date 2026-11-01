// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

/**
 * What a scan start the system refused means, and how soon to try it again.
 *
 * Bluetooth throttles scan starts per app (five in a thirty-second window) and reports the refusal
 * only through `ScanCallback.onScanFailed`, with no exception and no user-visible signal. An app
 * that ignores it looks like a scan that found nothing, which is the exact complaint this avoids.
 *
 * The codes are copied rather than read from `ScanCallback` because the newest one needs API 34
 * and these are values the Bluetooth stack defines, not a platform release.
 */
internal object ScanFailures {
    /** A scan this callback already started. */
    const val ALREADY_STARTED = 1

    /** Throttled: too many scan starts in the window, or the app is not allowed to scan now. */
    const val APPLICATION_REGISTRATION_FAILED = 2

    /** A Bluetooth stack error, usually transient. */
    const val INTERNAL_ERROR = 3

    /** The phone cannot scan with the requested settings; retrying cannot help. */
    const val FEATURE_UNSUPPORTED = 4

    /** Every scanner is taken, typically by another app scanning right now. */
    const val OUT_OF_HARDWARE_RESOURCES = 5

    /** The system's explicit version of [APPLICATION_REGISTRATION_FAILED] (API 34+). */
    const val SCANNING_TOO_FREQUENTLY = 6

    /** No wait helps: the refused start is not worth repeating. */
    const val NO_RETRY = -1L

    /** Enough retries to ride out one throttle window and a stuck stack; more would only burn the next. */
    const val MAX_ATTEMPTS = 3

    /** The throttle counts starts in a thirty-second window: anything sooner is refused again. */
    const val THROTTLE_DELAY_MS = 31_000L

    /** First delay for the failures a quick retry can clear. */
    const val FIRST_DELAY_MS = 5_000L
    const val MAX_DELAY_MS = 20_000L

    /** For log lines that name the wait in seconds. */
    const val MILLIS_PER_SECOND = 1_000L

    /** Enough doublings to pass [MAX_DELAY_MS]. */
    private const val MAX_DOUBLINGS = 4

    fun isRetryable(errorCode: Int): Boolean =
        when (errorCode) {
            ALREADY_STARTED, FEATURE_UNSUPPORTED -> false
            else -> true
        }

    /** The delay before retry number [attempt] (1 for the first). */
    fun delayMs(
        errorCode: Int,
        attempt: Int,
    ): Long =
        when (errorCode) {
            APPLICATION_REGISTRATION_FAILED, SCANNING_TOO_FREQUENTLY -> THROTTLE_DELAY_MS
            else -> (FIRST_DELAY_MS shl (attempt - 1).coerceIn(0, MAX_DOUBLINGS)).coerceAtMost(MAX_DELAY_MS)
        }

    /** One log line an owner can act on, wording the raw code. */
    fun message(errorCode: Int): String =
        when (errorCode) {
            ALREADY_STARTED -> "Scan failed: a scan is already running"
            APPLICATION_REGISTRATION_FAILED -> "Scan failed: the system refused a new scan (too many starts in a row)"
            INTERNAL_ERROR -> "Scan failed: Bluetooth stack error"
            FEATURE_UNSUPPORTED -> "Scan failed: the phone does not support these scan settings"
            OUT_OF_HARDWARE_RESOURCES -> "Scan failed: no free scanner (another app may be scanning)"
            SCANNING_TOO_FREQUENTLY -> "Scan failed: the system is throttling scans (too many starts in a row)"
            else -> "Scan failed with error $errorCode"
        }
}
