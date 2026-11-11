// TestTempTest.kt: JVM tests for core/TestTemp (/testtemp argument parsing). Docs: docs/WORKFLOW.md §2.

package com.nandan.cctv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TestTempTest {
    @Test
    fun batteryWithinValidRange() {
        assertEquals(TestTemp(51.0, cpu = false), TestTemp.parse("51", null))
    }

    @Test
    fun batteryAbove100Rejected() {
        // The battery monitor ignores readings above 100 °C, so a fake 120 would do nothing.
        assertNull(TestTemp.parse("120", null))
    }

    @Test
    fun cpuUpTo150() {
        assertEquals(TestTemp(120.0, cpu = true), TestTemp.parse("cpu", "120"))
        assertNull(TestTemp.parse("cpu", "151"))
    }

    @Test
    fun garbageRejected() {
        assertNull(TestTemp.parse(null, null))
        assertNull(TestTemp.parse("hot", null))
        assertNull(TestTemp.parse("cpu", null))
        assertNull(TestTemp.parse("0", null))
    }
}
