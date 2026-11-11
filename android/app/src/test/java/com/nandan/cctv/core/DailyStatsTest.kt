// DailyStatsTest.kt: JVM tests for core/DailyStats (counters, JSON, when the daily summary is due).
// Docs: docs/WORKFLOW.md §2.

package com.nandan.cctv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DailyStatsTest {
    private val tz = TimeZone.getTimeZone("Asia/Kolkata")
    private fun at(s: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = tz }.parse(s)!!.time

    @Test
    fun summaryDueOncePerDay() {
        assertTrue(DailyStats.summaryDue(at("2026-11-12 09:00"), 9, "2026-11-11", tz))
        assertFalse(DailyStats.summaryDue(at("2026-11-12 09:01"), 9, "2026-11-12", tz))  // already sent today
        assertFalse(DailyStats.summaryDue(at("2026-11-12 08:59"), 9, "2026-11-11", tz))  // too early
        assertFalse(DailyStats.summaryDue(at("2026-11-11 10:00"), 9, "2026-11-12", tz))  // clock jumped back
        assertTrue(DailyStats.summaryDue(at("2026-11-12 09:00"), 9, null, tz))           // never sent
    }

    @Test
    fun summaryTextHasAllCounters() {
        val s = DailyStats()
        repeat(17) { s.motions++ }
        s.clips = 12; s.alerts = 9; s.restarts = 2; s.offlineMin = 41
        s.noteTemp(48.5); s.noteTemp(52.25); s.noteTemp(50.0)
        val t = s.summaryText("Storage: 819 files, 295/1200 MB")
        for (part in listOf("17", "12", "9", "2", "41", "52.3", "Storage: 819 files, 295/1200 MB")) {
            assertTrue("missing $part in:\n$t", t.contains(part))
        }
    }

    @Test
    fun jsonRoundTripAndReset() {
        val s = DailyStats()
        s.motions = 5; s.clips = 4; s.alerts = 3; s.restarts = 2; s.offlineMin = 1; s.noteTemp(44.0)
        val back = DailyStats.fromJson(s.toJson())
        assertEquals(s.toJson(), back.toJson())
        back.reset()
        assertEquals(DailyStats().toJson(), back.toJson())
        assertEquals(DailyStats().toJson(), DailyStats.fromJson("{oops").toJson()) // corrupt → fresh
        assertEquals(DailyStats().toJson(), DailyStats.fromJson(null).toJson())
    }
}
