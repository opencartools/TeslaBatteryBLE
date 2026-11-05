// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

/**
 * Decides whether a link drop should raise the "closure left open" alert.
 *
 * A drop is routine, so the alert is about what the car said, not about the drop: any door, trunk or
 * tonneau open on the current connection arms it, and the next drop beeps once. The same open stretch
 * stays quiet across retries and flapping reconnects. It re-arms when every closure is closed, or when
 * the link has been continuously up for [stableMillis] by the time of the next drop, so a later departure
 * beeps again.
 *
 * Only a status received on the current connection counts: a status left over from an earlier connection
 * can be up to 13 h old. [open] is null until such a status arrives. The state is in memory only, so a
 * process death or reboot forgets it.
 */
internal class ClosureAlertEpisodes(
    private val stableMillis: Long = STABLE_MILLIS,
) {
    private class Episode(
        /** The last closure state seen on the current connection; null until one arrives. */
        var open: Boolean? = null,
        var alerted: Boolean = false,
        /** When the current connection became ready; null outside a connection. */
        var readyAtMillis: Long? = null,
    )

    private val episodes = mutableMapOf<String, Episode>()

    /** A new connection has no status yet, and its stable-up clock starts now. */
    fun onConnectionStarted(
        bleName: String,
        nowMillis: Long,
    ) {
        val episode = episodes.getOrPut(bleName) { Episode() }
        episode.open = null
        episode.readyAtMillis = nowMillis
    }

    /** Feeds a VCSEC status received on the current connection. */
    fun onStatus(
        bleName: String,
        closureOpen: Boolean,
    ) {
        val episode = episodes.getOrPut(bleName) { Episode() }
        val wasShut = episode.open == false
        episode.open = closureOpen
        // A fresh open after every closure was shut is a new stretch, so it may beep again.
        if (!closureOpen || wasShut) episode.alerted = false
    }

    /**
     * True when this drop should beep, and spends the open stretch; retries and quick flaps stay silent.
     * A link that was up for [stableMillis] counts as a return and re-arms before the decision.
     */
    fun onLinkDropped(
        bleName: String,
        nowMillis: Long,
        enabled: Boolean,
    ): Boolean {
        val episode = episodes[bleName] ?: return false
        val readyAt = episode.readyAtMillis
        if (episode.open == true && readyAt != null && nowMillis - readyAt >= stableMillis) {
            episode.alerted = false
        }
        val alert = enabled && episode.open == true && !episode.alerted
        if (alert) episode.alerted = true
        // The connection is gone; a later drop needs a status received on its own connection.
        episode.open = null
        episode.readyAtMillis = null
        return alert
    }

    /** Forgets every episode, for a deliberate teardown such as tracking or Bluetooth being switched off. */
    fun disarmAll() {
        episodes.clear()
    }

    companion object {
        /** A link that stays up this long counts as a return; a later departure can beep again. */
        const val STABLE_MILLIS = 60_000L
    }
}
