// StorageMathTest.kt: JVM tests for core/StorageMath (storage rate and days left). Docs: docs/WORKFLOW.md §2.

package com.nandan.cctv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageMathTest {
    private val mb = 1_048_576L

    @Test
    fun retentionFromRate() {
        // 600 MB used of a 1200 MB cap, 100 MB/day → 6 days until the cap starts deleting the oldest.
        assertEquals(6.0, retentionDays(600, 1200, 100.0)!!, 0.001)
        assertEquals(0.0, retentionDays(1300, 1200, 100.0)!!, 0.001) // over the cap: already deleting
    }

    @Test
    fun noRateNoEstimate() {
        assertNull(retentionDays(600, 1200, 0.0))
        assertNull(retentionDays(600, 1200, -1.0))
    }

    @Test
    fun mbPerDayUsesLastThreeCompleteDays() {
        val sizes = mapOf(
            "2026-11-06" to 900 * mb,  // older than the last 3 complete days: ignored
            "2026-11-08" to 100 * mb,
            "2026-11-09" to 200 * mb,
            "2026-11-10" to 300 * mb,
            "2026-11-11" to 50 * mb,   // today: incomplete, ignored
        )
        assertEquals(200.0, mbPerDay(sizes, today = "2026-11-11"), 0.001)
        assertEquals(150.0, mbPerDay(mapOf("2026-11-10" to 150 * mb), today = "2026-11-11"), 0.001)
        assertEquals(0.0, mbPerDay(mapOf("2026-11-11" to 50 * mb), today = "2026-11-11"), 0.001)
    }

    @Test
    fun outlookBelowCapShowsDaysUntilCap() {
        assertEquals("~6 days until the cap (then oldest deleted)", storageOutlook(600, 1200, 100.0))
    }

    @Test
    fun outlookAtCapShowsHistoryKept() {
        // At or over the cap, "days left" would always be 0: show how much history the cap holds instead.
        assertEquals("at cap: keeps about 20 days of history", storageOutlook(1200, 1200, 60.0))
        assertEquals("at cap: keeps about 20 days of history", storageOutlook(1250, 1200, 60.0))
    }

    @Test
    fun outlookWithoutRate() {
        assertEquals("rate not known yet", storageOutlook(600, 1200, 0.0))
        assertEquals("at cap, rate not known yet", storageOutlook(1200, 1200, 0.0))
    }
}
