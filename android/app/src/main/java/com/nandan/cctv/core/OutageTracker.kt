package com.nandan.cctv.core

/** A stretch of time Telegram couldn't be reached. Times are wall-clock milliseconds. */
data class Outage(val startMs: Long, val endMs: Long, val motions: Int)

/**
 * Notices when the bot loses Telegram and when it comes back. Blips shorter than
 * [minOutageMs] are not reported. Counts motions seen while offline, for the
 * "was offline" summary.
 */
class OutageTracker(private val minOutageMs: Long = 60_000) {
    private var startMs = -1L
    private var motions = 0

    val offline get() = startMs >= 0

    fun onFailure(nowMs: Long) {
        if (startMs < 0) {
            startMs = nowMs
            motions = 0
        }
    }

    /** Returns the outage that just ended, or null if there was none worth reporting. */
    fun onSuccess(nowMs: Long): Outage? {
        if (startMs < 0) return null
        val o = Outage(startMs, nowMs, motions)
        startMs = -1
        motions = 0
        return if (o.endMs - o.startMs >= minOutageMs) o else null
    }

    fun noteMotion() {
        if (offline) motions++
    }
}
