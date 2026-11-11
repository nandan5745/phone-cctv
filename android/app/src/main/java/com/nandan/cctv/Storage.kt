// Storage.kt: Clip folder housekeeping: size cap (oldest first), free space, broken-clip cleanup at start.
// Used by CameraService. Docs: docs/ARCHITECTURE.md §5; MP4 check: docs/INCIDENTS.md S2.

package com.nandan.cctv

import android.os.StatFs
import java.io.File
import java.io.RandomAccessFile

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

    /**
     * Run at startup: removes leftovers of a crash or power loss while recording.
     * - 0-byte files and half-written ".tmp" files
     * - .mp4 files that aren't complete. An MP4 is a chain of boxes (size + type).
     *   This phone's MediaRecorder reserves the "moov" index box at the start and fills
     *   in the box sizes when recording stops. A clip cut off mid-way has an "mdat"
     *   (video data) box whose size is 0 or bigger than what is on disk.
     * The .jpg taken when motion started is kept: it's still a valid photo.
     */
    fun deleteBrokenClips() {
        val files = clipsDir.listFiles() ?: return
        var deleted = 0
        for (f in files) {
            if (!f.isFile) continue
            val broken = f.length() == 0L || f.name.endsWith(".tmp") ||
                (f.name.endsWith(".mp4") && !isCompleteMp4(f))
            if (broken) {
                FileLog.w("deleting broken file ${f.name} (${f.length() / 1024} KB)")
                if (f.delete()) deleted++
            }
        }
        if (deleted > 0) FileLog.i("startup cleanup: deleted $deleted broken files")
    }

    /** (docs/INCIDENTS.md S2) True if the file has a "moov" box and its top-level boxes add up exactly to its size. */
    private fun isCompleteMp4(f: File): Boolean = try {
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            var pos = 0L
            var found = false
            while (pos + 8 <= len) {
                raf.seek(pos)
                var size = raf.readInt().toLong() and 0xFFFFFFFFL
                val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)
                if (type == "moov") found = true
                if (size == 1L) size = raf.readLong()       // 64-bit size follows
                else if (size == 0L) break                   // size not written yet: incomplete
                if (size < 8) break                          // corrupt
                pos += size
            }
            found && pos == len
        }
    } catch (e: Exception) {
        false
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
