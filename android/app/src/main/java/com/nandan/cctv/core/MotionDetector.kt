// MotionDetector.kt: core (no android.*): compares 80x60 grayscale grids of camera frames; used by CameraService.
// Tests: MotionDetectorTest. Docs: docs/ARCHITECTURE.md §1.

package com.nandan.cctv.core

/**
 * Compares tiny grayscale versions of consecutive frames.
 *
 * A camera frame in NV21 format starts with the "Y plane": one brightness byte per
 * pixel, row by row. That is already a grayscale image, so we read it directly.
 * We shrink it to 80x60 cells (each cell = average of 2x2 pixels, which smooths
 * camera noise) and count cells whose brightness changed by more than pixelDiff.
 *
 * Each frame's average brightness is subtracted before comparing, so the camera's
 * auto-exposure slowly brightening or darkening the whole picture is not "motion".
 */
class MotionDetector(private val cols: Int = 80, private val rows: Int = 60) {
    private var cur = IntArray(cols * rows)
    private var prev = IntArray(cols * rows)
    private var prevMean = 0
    private var hasPrev = false

    /** Average brightness (0-255) of the last frame analysed; used for darkness detection. */
    var lastMean = 0
        private set

    /** Forget the previous frame (after the camera restarts or a clip ends). */
    fun reset() {
        hasPrev = false
    }

    /** Returns the share (0..1) of changed cells, or -1 if there is no previous frame yet. */
    fun changedShare(nv21: ByteArray, width: Int, height: Int, pixelDiff: Int): Float {
        val sx = width / cols
        val sy = height / rows
        var sum = 0
        var i = 0
        for (r in 0 until rows) {
            val y0 = (r * sy + sy / 2) * width
            for (c in 0 until cols) {
                val p = y0 + c * sx + sx / 2
                val v = ((nv21[p].toInt() and 0xFF) + (nv21[p + 1].toInt() and 0xFF) +
                    (nv21[p + width].toInt() and 0xFF) + (nv21[p + width + 1].toInt() and 0xFF)) shr 2
                cur[i++] = v
                sum += v
            }
        }
        val mean = sum / cur.size
        lastMean = mean

        var result = -1f
        if (hasPrev) {
            var changed = 0
            for (k in cur.indices) {
                val d = (cur[k] - mean) - (prev[k] - prevMean)
                if (d > pixelDiff || d < -pixelDiff) changed++
            }
            result = changed.toFloat() / cur.size
        }
        val t = prev; prev = cur; cur = t // swap arrays, no allocation
        prevMean = mean
        hasPrev = true
        return result
    }
}
