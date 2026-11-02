// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

/**
 * Decides whether a dropped link should raise the "car left unlocked" alert.
 *
 * An episode is a stretch where the last VCSEC status says the car is fully unlocked with nobody
 * inside. An episode alerts at most once, however many reconnects, failed retries or further drops
 * follow; any status that is locked or reports someone present ends it, so the next unlock (or the
 * next time the driver leaves) arms afresh.
 *
 * The state is deliberately in memory only: a process death or a reboot forgets it, so a link that
 * was already gone before the process started cannot alert after the fact.
 */
internal class UnlockAlerts {
    private class Episode(
        var armed: Boolean = false,
        var alerted: Boolean = false,
    )

    private val episodes = mutableMapOf<String, Episode>()

    /** Feeds the latest VCSEC status. Only a full unlock with nobody present arms the alert. */
    fun onStatus(
        bleName: String,
        locked: Boolean,
        userPresent: Boolean,
    ) {
        val episode = episodes.getOrPut(bleName) { Episode() }
        val armed = !locked && !userPresent
        // A new arming starts a new episode, so a car that locked or gained a driver in between can alert again.
        if (armed && !episode.armed) episode.alerted = false
        episode.armed = armed
    }

    /**
     * True when this link drop should alert, and records that it did so retries stay silent. [enabled] is
     * the owner's switch; [trackingEnabled] and [bluetoothOff] are the exclusions the controller can see:
     * an alert suppressed by any of them does not consume the episode, so it can still fire once the car
     * stays unlocked and empty after tracking comes back or the radio returns.
     */
    fun onLinkDropped(
        bleName: String,
        enabled: Boolean,
        trackingEnabled: Boolean,
        bluetoothOff: Boolean,
    ): Boolean {
        val episode = episodes[bleName] ?: return false
        val alert = enabled && trackingEnabled && !bluetoothOff && episode.armed && !episode.alerted
        if (alert) episode.alerted = true
        return alert
    }

    /** Forgets every episode, for a deliberate teardown such as tracking or Bluetooth being switched off. */
    fun disarmAll() {
        episodes.clear()
    }
}
