// ClipSlotTest.kt: JVM tests for core/ClipSlot (one /clip at a time). Docs: docs/WORKFLOW.md §2.

package com.nandan.cctv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipSlotTest {
    @Test
    fun secondRequestWhilePendingRefused() {
        val s = ClipSlot()
        assertTrue(s.request(111, 20))
        assertFalse(s.request(222, 5))            // the first asker keeps the slot
        assertEquals(ClipSlot.Request(111, 20), s.take())
    }

    @Test
    fun freeAgainAfterTake() {
        val s = ClipSlot()
        s.request(111, 20); s.take()
        assertNull(s.take())
        assertTrue(s.request(222, 5))
    }
}
