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
    private val warnC: Double = 40.0,
    private val criticalC: Double = 45.0,
    private val emergencyC: Double = 50.0,
    private val resumeC: Double = 42.0,
    private val hysteresisC: Double = 2.0,
    private val lowPct: Int = 30,
    private val lowVoltageMv: Int = 3600,
) {
    var tempState = TempState.NORMAL
        private set
    var maxTempC = 0.0
        private set

    private var lowPctWarned = false
    private var lowVoltageWarned = false
    private var wasCharging: Boolean? = null
    private var lastHealth: String? = null

    fun resetMax() {
        maxTempC = 0.0
    }

    fun update(s: BatterySample): List<BatteryEvent> {
        val events = mutableListOf<BatteryEvent>()
        if (s.tempC > 0.0 && s.tempC <= 100.0) {
            if (s.tempC > maxTempC) maxTempC = s.tempC
            updateTemp(s.tempC, events)
        }

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

    private fun updateTemp(t: Double, events: MutableList<BatteryEvent>) {
        val risen = when {
            t >= emergencyC -> TempState.EMERGENCY
            t >= criticalC -> TempState.CRITICAL
            t >= warnC -> TempState.WARNING
            else -> TempState.NORMAL
        }
        val before = tempState
        if (risen > before) {
            tempState = risen
            events += BatteryEvent.Notify(
                when (risen) {
                    TempState.WARNING -> "🌡️ Battery warm: ${fmt(t)} °C"
                    TempState.CRITICAL -> "🔥 Battery HOT: ${fmt(t)} °C. Check the charger and airflow."
                    else -> "🚨 Battery ${fmt(t)} °C: camera stopped to cool down."
                }
            )
            if (risen == TempState.EMERGENCY) events += BatteryEvent.PauseForHeat
            return
        }
        // Cooling: leave each level only once clearly below it.
        while (tempState != TempState.NORMAL && t < exitBelow(tempState)) {
            tempState = TempState.values()[tempState.ordinal - 1]
        }
        if (before == TempState.EMERGENCY && tempState != TempState.EMERGENCY) {
            events += BatteryEvent.Notify("❄️ Cooled to ${fmt(t)} °C: camera back on.")
            events += BatteryEvent.ResumeAfterHeat
        }
        if (before != TempState.NORMAL && tempState == TempState.NORMAL) {
            events += BatteryEvent.Notify("✅ Battery temperature normal: ${fmt(t)} °C")
        }
    }

    private fun exitBelow(s: TempState) = when (s) {
        TempState.WARNING -> warnC - hysteresisC
        TempState.CRITICAL -> criticalC - hysteresisC
        TempState.EMERGENCY -> resumeC
        TempState.NORMAL -> Double.NEGATIVE_INFINITY
    }

    private fun fmt(t: Double) = "%.1f".format(t)
}
