// MotionDetectorTest.kt: JVM tests for core/MotionDetector (run by scripts/jvmtest.sh and CI). Docs: docs/WORKFLOW.md §2.

package com.nandan.cctv.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionDetectorTest {
    private val w = 640
    private val h = 480

    /** NV21 frame: Y plane from [fill] (clamped to 0..255), UV plane zeroed. */
    private fun frame(fill: (x: Int, y: Int) -> Int): ByteArray {
        val f = ByteArray(w * h * 3 / 2)
        for (y in 0 until h) for (x in 0 until w) f[y * w + x] = fill(x, y).coerceIn(0, 255).toByte()
        return f
    }

    private val base: (Int, Int) -> Int = { x, y -> 60 + (x * 7 + y * 3) % 80 } // textured scene

    @Test
    fun firstFrameReturnsMinusOne() {
        assertEquals(-1f, MotionDetector().changedShare(frame(base), w, h, 25), 0f)
    }

    @Test
    fun identicalFramesGiveZero() {
        val d = MotionDetector()
        d.changedShare(frame(base), w, h, 25)
        assertEquals(0f, d.changedShare(frame(base), w, h, 25), 0f)
    }

    @Test
    fun uniformBrightnessShiftIgnored() {
        val d = MotionDetector()
        d.changedShare(frame(base), w, h, 25)
        assertEquals(0f, d.changedShare(frame { x, y -> base(x, y) + 40 }, w, h, 25), 0f)
    }

    @Test
    fun blockChangeCounted() {
        val d = MotionDetector()
        d.changedShare(frame(base), w, h, 25)
        // 160x120 px in the top-left = 20x15 of the 80x60 cells
        val share = d.changedShare(frame { x, y -> base(x, y) + if (x < 160 && y < 120) 100 else 0 }, w, h, 25)
        assertEquals(300f / 4800, share, 0.01f)
    }

    @Test
    fun lastMeanReported() {
        val d = MotionDetector()
        d.changedShare(frame { _, _ -> 77 }, w, h, 25)
        assertEquals(77, d.lastMean)
    }
}
