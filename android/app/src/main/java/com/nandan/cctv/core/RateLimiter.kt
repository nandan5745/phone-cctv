// RateLimiter.kt: core (no android.*): at most [max] events per [windowMs] per key (a Telegram user id),
// sliding window. Used by Bot (Task 8) against command spam and repeated stranger notices. Tests: RateLimiterTest.

package com.nandan.cctv.core

class RateLimiter(val max: Int, val windowMs: Long) {
    private val stamps = HashMap<Long, ArrayDeque<Long>>()

    /** Keys currently remembered (for tests). */
    val keyCount get() = stamps.size

    /** True (and counted) if [key] is still under the limit at [nowMs]. */
    fun tryAcquire(key: Long, nowMs: Long): Boolean {
        if (stamps.size > 256) stamps.values.removeAll { it.isEmpty() || it.last() <= nowMs - windowMs } // forget old strangers
        val q = stamps.getOrPut(key) { ArrayDeque() }
        while (q.isNotEmpty() && q.first() <= nowMs - windowMs) q.removeFirst()
        if (q.size >= max) return false
        q.addLast(nowMs)
        return true
    }
}
