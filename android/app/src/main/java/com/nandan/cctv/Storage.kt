package com.nandan.cctv

import android.os.StatFs
import java.io.File

/** Clip folder housekeeping: size cap (oldest deleted first) and free-space check. */
object Storage {
    private const val MB = 1_048_576L
    val clipsDir: File = File(FileLog.dir, "clips")

    fun freeMB(): Long = StatFs(FileLog.dir.apply { mkdirs() }.path).availableBytes / MB

    /** Files are named by time (YYYY-MM-DD_HH-MM-SS), so sorting by name = oldest first. */
    private fun clips(): List<File> =
        clipsDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    /** Returns (file count, total MB). */
    fun stats(): Pair<Int, Long> {
        val files = clips()
        return files.size to files.sumOf { it.length() } / MB
    }

    /** Deletes the oldest files until the folder is under capMB. */
    fun enforceCap(capMB: Int) {
        val files = clips()
        var total = files.sumOf { it.length() }
        val cap = capMB * MB
        var deleted = 0
        for (f in files) {
            if (total <= cap) break
            val len = f.length()
            if (f.delete()) {
                total -= len
                deleted++
            }
        }
        if (deleted > 0) FileLog.i("storage cap: deleted $deleted oldest files, clips now ${total / MB} MB")
    }
}
