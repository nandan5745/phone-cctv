// DailyStats.kt: core (no android.*): counters for the daily summary (motions, clips, alerts, restarts,
// offline minutes, max CPU temperature), stored as JSON in prefs; when the summary is due.
// Used by CameraService and Bot (/summary). Tests: DailyStatsTest.

package com.nandan.cctv.core

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What happened since the last daily summary. */
class DailyStats {
    var motions = 0
    var clips = 0
    var alerts = 0
    var restarts = 0
    var offlineMin = 0L
    var maxTempC = 0.0
        private set

    fun noteTemp(c: Double) {
        if (c > maxTempC) maxTempC = c
    }

    fun summaryText(storageLine: String): String =
        "📅 Daily summary (since the last one)\n" +
            "Motions: $motions, clips: $clips, alerts sent: $alerts\n" +
            "Restarts: $restarts, offline: $offlineMin min\n" +
            "Max CPU temp: ${"%.1f".format(Locale.US, maxTempC)} °C\n" +
            storageLine

    fun reset() {
        motions = 0; clips = 0; alerts = 0; restarts = 0; offlineMin = 0; maxTempC = 0.0
    }

    fun toJson(): String = JSONObject()
        .put("motions", motions).put("clips", clips).put("alerts", alerts)
        .put("restarts", restarts).put("offlineMin", offlineMin).put("maxTempC", maxTempC)
        .toString()

    companion object {
        /** A missing or corrupt record starts from zero. */
        fun fromJson(json: String?): DailyStats {
            val s = DailyStats()
            if (json == null) return s
            try {
                val j = JSONObject(json)
                s.motions = j.optInt("motions"); s.clips = j.optInt("clips"); s.alerts = j.optInt("alerts")
                s.restarts = j.optInt("restarts"); s.offlineMin = j.optLong("offlineMin")
                s.noteTemp(j.optDouble("maxTempC", 0.0))
            } catch (e: Exception) {
                return DailyStats()
            }
            return s
        }

        fun day(nowMs: Long, tz: TimeZone): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }.format(Date(nowMs))

        /**
         * Due once per day at or after [hour]:00. Never twice on the same day, and not when the
         * clock is behind the day it was last sent (a clock that jumped back after a reboot).
         */
        fun summaryDue(nowMs: Long, hour: Int, lastSentDay: String?, tz: TimeZone): Boolean {
            val today = day(nowMs, tz)
            if (lastSentDay != null && today <= lastSentDay) return false
            val h = SimpleDateFormat("H", Locale.US).apply { timeZone = tz }.format(Date(nowMs)).toInt()
            return h >= hour
        }
    }
}
