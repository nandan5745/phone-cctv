package com.nandan.cctv

import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny logger that writes to /sdcard/cctv/log.txt (there is no adb on this phone).
 * When the file passes ~1 MB it is renamed to log.1.txt and a new one starts.
 * Read it from the Mac with:  ssh phone 'tail -f /sdcard/cctv/log.txt'
 */
object FileLog {
    private const val TAG = "cctv"
    private const val MAX_BYTES = 1_000_000L

    val dir: File = File(Environment.getExternalStorageDirectory(), "cctv")
    private val file = File(dir, "log.txt")
    private val old = File(dir, "log.1.txt")
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun i(msg: String) = write("I", msg, null)
    fun w(msg: String, t: Throwable? = null) = write("W", msg, t)
    fun e(msg: String, t: Throwable? = null) = write("E", msg, t)

    @Synchronized
    private fun write(level: String, msg: String, t: Throwable?) {
        val line = StringBuilder()
            .append(fmt.format(Date())).append(' ').append(level).append(' ')
            .append('[').append(Thread.currentThread().name).append("] ").append(msg)
        if (t != null) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            line.append('\n').append(sw.toString().trimEnd())
        }
        Log.println(if (level == "E") Log.ERROR else Log.INFO, TAG, line.toString())
        try {
            if (!dir.exists()) dir.mkdirs()
            if (file.length() > MAX_BYTES) {
                old.delete()
                file.renameTo(old)
            }
            FileWriter(file, true).use { it.write(line.append('\n').toString()) }
        } catch (e: Exception) {
            Log.e(TAG, "log write failed", e)
        }
    }
}
