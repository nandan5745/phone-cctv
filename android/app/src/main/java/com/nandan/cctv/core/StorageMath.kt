// StorageMath.kt: core (no android.*): clip storage per day and days until the size cap starts
// deleting the oldest files; used by CameraService for /status. Tests: StorageMathTest.

package com.nandan.cctv.core

private const val MB = 1_048_576.0

/** Days until [usedMb] reaches [capMb] at [mbPerDay] (0 when already at the cap); null without a rate. */
fun retentionDays(usedMb: Long, capMb: Int, mbPerDay: Double): Double? =
    if (mbPerDay <= 0) null else ((capMb - usedMb) / mbPerDay).coerceAtLeast(0.0)

/**
 * Mean MB/day over the last 3 complete days. [sizesByDay] maps "YYYY-MM-DD" to bytes;
 * [today] (still filling up) and days with no files are left out.
 */
fun mbPerDay(sizesByDay: Map<String, Long>, today: String): Double {
    val days = sizesByDay.keys.filter { it < today }.sorted().takeLast(3)
    if (days.isEmpty()) return 0.0
    return days.sumOf { sizesByDay.getValue(it) } / MB / days.size
}
