// BatteryInfo.kt: Reads Android's sticky battery broadcast (%, status, plug, °C, mV, health) for CameraService.
// The % and the temperature are unreliable on this phone: docs/PHONE.md, docs/INCIDENTS.md S4, S7.

package com.nandan.cctv

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * A snapshot of the battery. ACTION_BATTERY_CHANGED is a "sticky" broadcast:
 * registering with a null receiver just returns the latest value, no listener needed.
 */
class BatteryInfo private constructor(
    val pct: Int,
    val status: Int,
    val plugged: Int,
    val tempC: Double,
    val voltageMv: Int,
    val health: Int,
    /** Battery current in mA (+ = charging, - = draining), or null if the phone doesn't report it. */
    val currentMa: Int?,
) {
    /** True while the phone says it's charging or full. */
    val charging get() = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

    fun text(): String {
        val state = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT CHARGING"
            else -> "unknown"
        }
        val plug = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            0 -> "unplugged"
            else -> "plug=$plugged"
        }
        return "$pct% $state ($plug) ${"%.1f".format(tempC)}°C ${"%.2f".format(voltageMv / 1000.0)}V" +
            (currentMa?.let { " ${if (it > 0) "+" else ""}${it}mA" } ?: "") + " health=${healthText()}"
    }

    /** "good", "OVERHEAT", "DEAD", "OVER-VOLTAGE", "COLD", "FAILURE" or "unknown". */
    fun healthText(): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
        BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER-VOLTAGE"
        BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "FAILURE"
        else -> "unknown"
    }

    companion object {
        fun read(ctx: Context): BatteryInfo? {
            val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val microAmps = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
            return BatteryInfo(
                pct = if (level >= 0 && scale > 0) level * 100 / scale else -1,
                status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
                plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
                tempC = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0,
                voltageMv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
                health = b.getIntExtra(BatteryManager.EXTRA_HEALTH, 0),
                // Many old phones return 0 or Int.MIN_VALUE here, meaning "not supported".
                // Some kernels report mA instead of µA: small numbers are taken as mA already.
                currentMa = when {
                    microAmps == 0 || microAmps == Int.MIN_VALUE -> null
                    microAmps in -5000..5000 -> microAmps
                    else -> microAmps / 1000
                },
            )
        }
    }
}
