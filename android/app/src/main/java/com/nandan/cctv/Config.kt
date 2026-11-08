package com.nandan.cctv

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Settings, read from config.json.
 *
 * You drop /sdcard/cctv/config.json over SSH. On start the app copies it into its
 * private storage (only this app can read that) and deletes the /sdcard copy, because
 * every app on the phone can read /sdcard. Missing keys use the defaults below.
 */
class Config private constructor(j: JSONObject) {
    val telegramBotToken: String = j.optString("telegramBotToken", "")
    val telegramUserId: Long = j.optLong("telegramUserId", 0)

    /** How much a pixel's brightness (0-255) must change to count as "changed". */
    val motionPixelDiff: Int = j.optInt("motionPixelDiff", 25).coerceIn(1, 255)
    /** Share of changed pixels (0.02 = 2%) that counts as motion. */
    val motionThreshold: Double = j.optDouble("motionThreshold", 0.02).coerceIn(0.001, 1.0)
    /** Motion must be seen in this many analysed frames in a row (filters camera noise). */
    val motionFrames: Int = j.optInt("motionFrames", 2).coerceIn(1, 10)
    val analyzeFps: Int = j.optInt("analyzeFps", 2).coerceIn(1, 5)

    val alertCooldownSeconds: Int = j.optInt("alertCooldownSeconds", 120).coerceAtLeast(0)
    val clipSeconds: Int = j.optInt("clipSeconds", 20).coerceIn(5, 60)
    val storageCapMB: Int = j.optInt("storageCapMB", 1200).coerceAtLeast(50)
    val minFreeMB: Int = j.optInt("minFreeMB", 300).coerceAtLeast(50)

    /** Battery temperature thresholds in °C (warning, critical, emergency = camera paused, resume). */
    val tempWarnC: Double = j.optDouble("tempWarnC", 40.0)
    val tempCriticalC: Double = j.optDouble("tempCriticalC", 45.0)
    val tempEmergencyC: Double = j.optDouble("tempEmergencyC", 50.0)
    val tempResumeC: Double = j.optDouble("tempResumeC", 42.0)

    /** For the log. Never includes the bot token itself. */
    override fun toString() =
        "pixelDiff=$motionPixelDiff threshold=$motionThreshold frames=$motionFrames fps=$analyzeFps " +
            "clip=${clipSeconds}s cap=${storageCapMB}MB minFree=${minFreeMB}MB cooldown=${alertCooldownSeconds}s " +
            "temp=$tempWarnC/$tempCriticalC/$tempEmergencyC/$tempResumeC°C " +
            "telegram=${if (telegramBotToken.isNotEmpty() && telegramUserId != 0L) "set" else "not set"}"

    companion object {
        fun load(ctx: Context): Config {
            val public = File(FileLog.dir, "config.json")
            val private = File(ctx.filesDir, "config.json")
            if (public.exists()) {
                try {
                    val text = public.readText()
                    JSONObject(text) // check it parses before replacing the old one
                    private.writeText(text)
                    if (public.delete()) FileLog.i("imported config.json into private storage, deleted /sdcard copy")
                    else FileLog.w("imported config.json but could not delete the /sdcard copy")
                } catch (e: Exception) {
                    // Don't log e.message: for JSON errors it contains the file text (and the token).
                    FileLog.e("/sdcard/cctv/config.json is not valid JSON (${e.javaClass.simpleName}); kept old settings")
                }
            }
            val cfg = try {
                Config(if (private.exists()) JSONObject(private.readText()) else JSONObject())
            } catch (e: Exception) {
                FileLog.e("private config unreadable (${e.javaClass.simpleName}); using defaults")
                Config(JSONObject())
            }
            FileLog.i("config: $cfg")
            return cfg
        }
    }
}
