package com.nandan.cctv.core

/**
 * Temperature levels for one sensor. A level is entered as soon as its threshold is
 * reached, and left only once the reading is clearly below it (hysteresis), so a value
 * hovering around a threshold sends one message, not one per minute.
 * Readings outside (0, maxValidC] are ignored: broken or unsupported sensors.
 */
class HeatMonitor(
    private val label: String,
    private val warnC: Double,
    private val criticalC: Double,
    private val emergencyC: Double,
    private val resumeC: Double,
    private val hysteresisC: Double = 2.0,
    private val maxValidC: Double = 100.0,
) {
    var state = TempState.NORMAL
        private set
    var maxC = 0.0
        private set

    fun resetMax() {
        maxC = 0.0
    }

    fun update(t: Double): List<BatteryEvent> {
        if (t <= 0.0 || t > maxValidC) return emptyList()
        if (t > maxC) maxC = t
        val events = mutableListOf<BatteryEvent>()
        val risen = when {
            t >= emergencyC -> TempState.EMERGENCY
            t >= criticalC -> TempState.CRITICAL
            t >= warnC -> TempState.WARNING
            else -> TempState.NORMAL
        }
        val before = state
        if (risen > before) {
            state = risen
            events += BatteryEvent.Notify(
                when (risen) {
                    TempState.WARNING -> "🌡️ $label warm: ${fmt(t)} °C"
                    TempState.CRITICAL -> "🔥 $label HOT: ${fmt(t)} °C. Check the charger, sun and airflow."
                    else -> "🚨 $label ${fmt(t)} °C: camera stopped to cool down."
                }
            )
            if (risen == TempState.EMERGENCY) events += BatteryEvent.PauseForHeat
            return events
        }
        while (state != TempState.NORMAL && t < exitBelow(state)) {
            state = TempState.values()[state.ordinal - 1]
        }
        if (before == TempState.EMERGENCY && state != TempState.EMERGENCY) {
            events += BatteryEvent.Notify("❄️ $label cooled to ${fmt(t)} °C: camera back on.")
            events += BatteryEvent.ResumeAfterHeat
        }
        if (before != TempState.NORMAL && state == TempState.NORMAL) {
            events += BatteryEvent.Notify("✅ $label temperature normal: ${fmt(t)} °C")
        }
        return events
    }

    private fun exitBelow(s: TempState) = when (s) {
        TempState.WARNING -> warnC - hysteresisC
        TempState.CRITICAL -> criticalC - hysteresisC
        TempState.EMERGENCY -> resumeC
        TempState.NORMAL -> Double.NEGATIVE_INFINITY
    }

    private fun fmt(t: Double) = "%.1f".format(t)
}
