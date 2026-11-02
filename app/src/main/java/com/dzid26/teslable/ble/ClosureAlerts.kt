// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

/**
 * Decides whether a link drop should raise the "closure left open" alert.
 *
 * A drop is routine, so the alert is about what the car said, not about the drop: any door, trunk or
 * tonneau open on the current connection arms it, and the next drop beeps once. The same open stretch
 * stays quiet across retries and flapping reconnects. It re-arms when every closure is closed, or once the
 * link has been continuously up for long enough to count as a return, so a later departure beeps again.
 *
 * Only a status received on the current connection counts: a status left over from an earlier connection
 * can be up to 13 h old. The state is in memory only, so a process death or reboot forgets it.
 */
internal class ClosureAlerts {
    private class Episode(
        var open: Boolean = false,
        var fresh: Boolean = false,
        var alerted: Boolean = false,
    )

    private val episodes = mutableMapOf<String, Episode>()

    /** A new connection has no status yet, so nothing may arm the alert until one arrives on it. */
    fun onConnectionStarted(bleName: String) {
        episodes.getOrPut(bleName) { Episode() }.fresh = false
    }

    /** Feeds a VCSEC status received on the current connection. */
    fun onStatus(
        bleName: String,
        closureOpen: Boolean,
    ) {
        val episode = episodes.getOrPut(bleName) { Episode() }
        episode.fresh = true
        if (closureOpen) {
            // A fresh open after every closure was shut is a new stretch, so it may beep again.
            if (!episode.open) episode.alerted = false
            episode.open = true
        } else {
            episode.open = false
            episode.alerted = false
        }
    }

    /** True when this drop should beep, and spends the open stretch; retries and quick flaps stay silent. */
    fun onLinkDropped(
        bleName: String,
        enabled: Boolean,
    ): Boolean {
        val episode = episodes[bleName] ?: return false
        val alert = enabled && episode.fresh && episode.open && !episode.alerted
        if (alert) episode.alerted = true
        // The connection is gone; a later drop needs a status received on its own connection.
        episode.fresh = false
        return alert
    }

    /** The link stayed up long enough to count as a return, so a later departure can beep again. */
    fun onLinkUpStable(bleName: String) {
        val episode = episodes[bleName] ?: return
        if (episode.fresh && episode.open) episode.alerted = false
    }

    /** Forgets every episode, for a deliberate teardown such as tracking or Bluetooth being switched off. */
    fun disarmAll() {
        episodes.clear()
    }
}
