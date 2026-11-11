// BatteryMonitor.kt: core (no android.*): battery warnings (heat via HeatMonitor, low %, low voltage, charging,
// health). Used by CameraService every minute. Tests: BatteryMonitorTest. Docs: docs/INCIDENTS.md S3–S4.

package com.nandan.cctv.core

/** One battery reading. [health] is "good", "OVERHEAT", "DEAD", … or "unknown". */
data class BatterySample(
    val pct: Int,
    val charging: Boolean,
    val tempC: Double,
    val voltageMv: Int,
    val health: String,
    val nowMs: Long,
)

enum class TempState { NORMAL, WARNING, CRITICAL, EMERGENCY }

sealed class BatteryEvent {
    /** A message for Telegram. The caller appends the full battery details. */
    data class Notify(val text: String) : BatteryEvent()
    /** Too hot: stop recording and the camera. */
    object PauseForHeat : BatteryEvent()
    /** Cooled down after a pause: start the camera again. */
    object ResumeAfterHeat : BatteryEvent()
}

/**
 * Turns battery readings into warnings. Each warning is sent once, and only re-armed
 * after the reading has clearly recovered (hysteresis), so a value hovering around a
 * threshold doesn't send a message every minute.
 */
class BatteryMonitor(
    warnC: Double = 40.0,
    criticalC: Double = 45.0,
    emergencyC: Double = 50.0,
    resumeC: Double = 42.0,
    hysteresisC: Double = 2.0,
    private val lowPct: Int = 30,
    private val lowVoltageMv: Int = 3600,
) {
    private val heat = HeatMonitor("Battery", warnC, criticalC, emergencyC, resumeC, hysteresisC)
    val tempState get() = heat.state
    val maxTempC get() = heat.maxC

    private var lowPctWarned = false
    private var lowVoltageWarned = false
    private var wasCharging: Boolean? = null
    private var lastHealth: String? = null

    fun resetMax() = heat.resetMax()

    fun update(s: BatterySample): List<BatteryEvent> {
        val events = mutableListOf<BatteryEvent>()
        events += heat.update(s.tempC)

        if (s.pct in 0 until lowPct && !lowPctWarned) {
            lowPctWarned = true
            events += BatteryEvent.Notify("🪫 Battery low: ${s.pct}%")
        } else if (s.pct >= lowPct + 5) {
            lowPctWarned = false
        }

        if (s.voltageMv > 0) {
            if (s.voltageMv < lowVoltageMv && !lowVoltageWarned) {
                lowVoltageWarned = true
                events += BatteryEvent.Notify(
                    "🪫 Battery voltage LOW: ${"%.2f".format(s.voltageMv / 1000.0)} V " +
                        "(phone shows ${s.pct}%, which is wrong on this phone). It may shut down soon."
                )
            } else if (s.voltageMv >= lowVoltageMv + 150) {
                lowVoltageWarned = false
            }
        }

        val prev = wasCharging
        if (prev != false && !s.charging) events += BatteryEvent.Notify("⚠️ Phone is not charging")
        if (prev == false && s.charging) events += BatteryEvent.Notify("🔌 Charging again")
        wasCharging = s.charging

        val h = lastHealth
        if ((h != null && s.health != h) || (h == null && s.health != "good" && s.health != "unknown")) {
            events += BatteryEvent.Notify("⚠️ Battery health: ${s.health}")
        }
        lastHealth = s.health
        return events
    }
}
