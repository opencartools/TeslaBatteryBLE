// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

/**
 * Decides whether a dropped link should raise the "car left unlocked" alert.
 *
 * A link drop alone is normal: the car ends idle links after about half a minute and reconnects on its
 * own, so the alert waits [waitMillis] for a successful reconnect. An episode is a stretch where the last
 * VCSEC status was fully unlocked with a door or trunk open, or with nobody inside. A reconnect cancels
 * the wait; a status that is locked, shut and occupied ends the episode. An episode alerts at most once.
 *
 * The state is deliberately in memory only: a process death or a reboot forgets it, so a link that was
 * already gone before the process started cannot alert after the fact.
 */
internal class UnlockAlerts(
    private val waitMillis: Long = DEFAULT_WAIT_MILLIS,
) {
    private class Episode(
        var eligible: Boolean = false,
        var alerted: Boolean = false,
        var droppedAtMillis: Long? = null,
    )

    private val episodes = mutableMapOf<String, Episode>()

    /** Feeds the latest VCSEC status; only a full unlock that is also open or empty starts an episode. */
    fun onStatus(
        bleName: String,
        unlocked: Boolean,
        closureOpen: Boolean,
        userPresent: Boolean,
    ) {
        val episode = episodes.getOrPut(bleName) { Episode() }
        when {
            !unlocked || (!closureOpen && userPresent) -> {
                episode.eligible = false
                episode.alerted = false
                episode.droppedAtMillis = null
            }

            !episode.eligible -> {
                // A new unlock-away episode, even if one before it had already alerted.
                episode.eligible = true
                episode.alerted = false
                episode.droppedAtMillis = null
            }
        }
    }

    /** Records the drop that becomes an alert after [waitMillis] without a successful reconnect. */
    fun onLinkDropped(
        bleName: String,
        nowMillis: Long,
    ) {
        val episode = episodes[bleName] ?: return
        if (episode.eligible && !episode.alerted && episode.droppedAtMillis == null) {
            episode.droppedAtMillis = nowMillis
        }
    }

    /** A successful reconnect ends the wait: the car is still reachable, so the owner has not walked away. */
    fun onLinkReady(bleName: String) {
        episodes[bleName]?.droppedAtMillis = null
    }

    /** Cancels a wait without spending the episode, for an alert switched off while the wait ran. */
    fun cancelPending(bleName: String) {
        episodes[bleName]?.droppedAtMillis = null
    }

    /** True once the link has been down for the whole wait and [enabled] still allows the alert; fires once. */
    fun due(
        bleName: String,
        nowMillis: Long,
        enabled: Boolean,
    ): Boolean {
        val episode = episodes[bleName] ?: return false
        val droppedAt = episode.droppedAtMillis
        val due =
            enabled &&
                droppedAt != null &&
                episode.eligible &&
                !episode.alerted &&
                nowMillis - droppedAt >= waitMillis
        if (due) {
            episode.alerted = true
            episode.droppedAtMillis = null
        }
        return due
    }

    /** Forgets every episode, for a deliberate teardown such as tracking or Bluetooth being switched off. */
    fun disarmAll() {
        episodes.clear()
    }

    companion object {
        /**
         * Five minutes without a reconnect. Measured drops mostly recover within 5-160 s; the wait sits well
         * past that churn, and the one real unlocked-away episode stayed down for hours.
         */
        const val DEFAULT_WAIT_MILLIS = 5 * 60_000L
    }
}
