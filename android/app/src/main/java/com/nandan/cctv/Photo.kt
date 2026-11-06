package com.nandan.cctv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import java.io.ByteArrayOutputStream

/**
 * Turns a camera JPEG into the photo sent to Telegram: rotated upright (the sensor is
 * mounted sideways in the phone) with the date and time drawn in the corner.
 * Runs on the Telegram sender thread, never on the camera thread.
 */
object Photo {
    fun upright(jpeg: ByteArray, rotation: Int, label: String): ByteArray {
        // RGB_565 uses half the memory of the default ARGB_8888 (600 KB vs 1.2 MB at 640x480).
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return jpeg
        val sw = src.width
        val sh = src.height
        val sideways = rotation == 90 || rotation == 270
        val out = Bitmap.createBitmap(if (sideways) sh else sw, if (sideways) sw else sh, Bitmap.Config.RGB_565)
        try {
            val canvas = Canvas(out)
            val m = Matrix()
            m.postRotate(rotation.toFloat())
            when (rotation) { // move the rotated picture back into view
                90 -> m.postTranslate(sh.toFloat(), 0f)
                180 -> m.postTranslate(sw.toFloat(), sh.toFloat())
                270 -> m.postTranslate(0f, sw.toFloat())
            }
            canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
            src.recycle()

            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = out.width / 20f
            }
            val pad = text.textSize / 3
            val bg = Paint().apply { color = Color.argb(150, 0, 0, 0) }
            canvas.drawRect(0f, out.height - text.textSize - 2 * pad, text.measureText(label) + 2 * pad, out.height.toFloat(), bg)
            canvas.drawText(label, pad, out.height - pad - text.descent(), text)

            val bytes = ByteArrayOutputStream(64 * 1024)
            out.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
            return bytes.toByteArray()
        } finally {
            if (!src.isRecycled) src.recycle()
            out.recycle()
        }
    }
}
