// RateLimiterTest.kt: JVM tests for core/RateLimiter (per-user sliding window). Docs: docs/WORKFLOW.md §2.

package com.nandan.cctv.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {
    @Test
    fun allowsUpToMaxInWindow() {
        val r = RateLimiter(10, 60_000)
        repeat(10) { assertTrue(r.tryAcquire(1, 1_000L + it)) }
        assertFalse(r.tryAcquire(1, 2_000))
    }

    @Test
    fun windowSlides() {
        val r = RateLimiter(10, 60_000)
        repeat(10) { r.tryAcquire(1, 0) }
        assertFalse(r.tryAcquire(1, 59_999))
        assertTrue(r.tryAcquire(1, 60_000))
    }

    @Test
    fun keysIndependent() {
        val r = RateLimiter(1, 60_000)
        assertTrue(r.tryAcquire(1, 0))
        assertFalse(r.tryAcquire(1, 1))
        assertTrue(r.tryAcquire(2, 1))
    }

    @Test
    fun expiredKeysAreForgotten() {
        // Strangers' ids must not pile up forever.
        val r = RateLimiter(1, 1_000)
        for (k in 1L..500L) r.tryAcquire(k, 0)
        r.tryAcquire(9_999, 5_000)
        assertTrue(r.keyCount <= 257)
    }
}
