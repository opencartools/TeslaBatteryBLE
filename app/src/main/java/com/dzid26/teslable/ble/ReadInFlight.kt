// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

/** How far an Infotainment read has come: [done] of [total] steps (the session handshake, when needed, and each reply). */
data class ReadProgress(
    val done: Int,
    val total: Int,
) {
    /** The share of the read that is done, 0 to 1. */
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Whether an Infotainment read (charge, drive, closures, climate, then location and tire pressure
 * when due, in sequence) is in flight for one car, and how far it has come, for the progress ring on
 * its card. The VCSEC status poll does not count.
 *
 * A read starts with [begin], which sets how many steps it expects: the replies, plus the session
 * handshake when the read has to wait for one ([sessionReady] counts that step). It is in flight while
 * a request is out ([onSent]) and goes off at every exit: a reply that sends no follow-up (the last
 * reply of the sequence, a failure, a refusal, a reply without data, or a follow-up that could not be
 * sent) via [onReply] with no [onSent] after it, the gate skipping the read because the car is asleep
 * ([onSkipped]), the link dropping ([onLinkDropped]), or no activity for [TIMEOUT_MS] ([expire]), so
 * it can never stay on. A read that turns out longer than expected (a location or tire pressure read
 * became due on the way) grows its total rather than overfilling the ring.
 * One instance per vehicle link, used on the main thread.
 *
 * @param onChange called with the new progress whenever it changes, null when the read is over.
 */
internal class ReadInFlight(
    private val onChange: (ReadProgress?) -> Unit = {},
) {
    private var lastActivityMs: Long? = null
    private var done = 0
    private var total = 0
    private var awaitingSession = false
    private var published: ReadProgress? = null

    /** True while a read is in flight. */
    val active: Boolean get() = lastActivityMs != null

    /** True while the read waits for its session handshake. */
    val waitingForSession: Boolean get() = active && awaitingSession

    /**
     * A read starts at [nowMs] expecting [replies] replies, after a session handshake first when
     * [afterHandshake]. A read already in flight keeps its progress: a refresh or a poll mid-read only
     * adds requests, which [onSent] counts by growing the total.
     */
    fun begin(
        nowMs: Long,
        replies: Int,
        afterHandshake: Boolean,
    ) {
        if (active) return
        awaitingSession = afterHandshake
        done = 0
        total = replies + if (afterHandshake) 1 else 0
        lastActivityMs = nowMs
        publish()
    }

    /** The session the read was waiting for is up: the handshake step is done. */
    fun sessionReady(nowMs: Long) {
        if (!waitingForSession) return
        awaitingSession = false
        done++
        lastActivityMs = nowMs
        publish()
    }

    /** A read request (the first or a follow-up) went out at [nowMs]. */
    fun onSent(nowMs: Long) {
        lastActivityMs = nowMs
        // One more request than expected: the read grew on the way.
        total = maxOf(total, done + 1)
        publish()
    }

    /** A read reply arrived. A follow-up sent after it (via [onSent]) keeps the read in flight; see [settle]. */
    fun onReply() {
        if (!active) return
        done++
        lastActivityMs = null
    }

    /** Ends the read when the reply just handled sent no follow-up; called after every reply is handled. */
    fun settle() {
        if (!active) clear()
    }

    /** The read was skipped because the car is asleep. */
    fun onSkipped() = clear()

    /** The link dropped or was closed. */
    fun onLinkDropped() = clear()

    /** Ends the read when nothing happened for [TIMEOUT_MS]; returns whether it did. */
    fun expire(nowMs: Long): Boolean {
        val last = lastActivityMs ?: return false
        if (nowMs - last < TIMEOUT_MS) return false
        clear()
        return true
    }

    private fun publish() {
        val next = ReadProgress(done, total)
        if (next == published) return
        published = next
        onChange(next)
    }

    private fun clear() {
        lastActivityMs = null
        awaitingSession = false
        done = 0
        total = 0
        if (published == null) return
        published = null
        onChange(null)
    }

    companion object {
        /** The same wait the command log gives a request before it counts as unanswered. */
        const val TIMEOUT_MS = CommandRecorder.TIMEOUT_MS
    }
}
