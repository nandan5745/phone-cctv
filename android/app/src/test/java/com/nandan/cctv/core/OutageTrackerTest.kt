// OutageTrackerTest.kt: JVM tests for core/OutageTracker. Docs: docs/WORKFLOW.md §2.

package com.nandan.cctv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutageTrackerTest {
    @Test
    fun shortBlipIsNotAnOutage() {
        val t = OutageTracker()
        t.onFailure(0)
        assertNull(t.onSuccess(30_000))
    }

    @Test
    fun outageReportedOnce() {
        val t = OutageTracker()
        t.onFailure(0)
        t.onFailure(70_000)
        assertEquals(Outage(0, 90_000, 0), t.onSuccess(90_000))
        assertNull(t.onSuccess(100_000))
    }

    @Test
    fun motionsCountedOnlyWhileOffline() {
        val t = OutageTracker()
        t.noteMotion(); t.noteMotion()
        t.onFailure(0)
        repeat(3) { t.noteMotion() }
        assertEquals(3, t.onSuccess(120_000)!!.motions)
    }
}
