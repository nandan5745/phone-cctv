package com.nandan.cctv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryMonitorTest {
    private var t = 0L
    private fun s(tempC: Double = 30.0, voltageMv: Int = 4200, charging: Boolean = true, pct: Int = 100, health: String = "good") =
        BatterySample(pct, charging, tempC, voltageMv, health, (t++) * 60_000)

    private fun texts(e: List<BatteryEvent>) = e.filterIsInstance<BatteryEvent.Notify>().map { it.text }

    @Test
    fun warningAt40() {
        val m = BatteryMonitor()
        assertEquals(emptyList<BatteryEvent>(), m.update(s(39.9)))
        val e = m.update(s(40.0))
        assertEquals(1, texts(e).size)
        assertTrue(texts(e)[0].startsWith("🌡️ Battery warm"))
        assertEquals(TempState.WARNING, m.tempState)
    }

    @Test
    fun noRepeatWhileWarm() {
        val m = BatteryMonitor()
        val all = (1..3).flatMap { m.update(s(41.0)) }
        assertEquals(1, texts(all).size)
    }

    @Test
    fun hysteresisOnCooling() {
        val m = BatteryMonitor()
        m.update(s(40.5))
        assertEquals(emptyList<BatteryEvent>(), m.update(s(39.0)))
        assertEquals(TempState.WARNING, m.tempState)
        val e = m.update(s(37.9))
        assertTrue(texts(e).single().startsWith("✅ Battery temperature normal"))
        assertEquals(TempState.NORMAL, m.tempState)
    }

    @Test
    fun criticalAt45() {
        val m = BatteryMonitor()
        assertTrue(texts(m.update(s(45.0))).single().startsWith("🔥 Battery HOT"))
        assertEquals(TempState.CRITICAL, m.tempState)
    }

    @Test
    fun emergencyPausesAndResumes() {
        val m = BatteryMonitor()
        val hot = m.update(s(50.0))
        assertTrue(texts(hot).single().startsWith("🚨"))
        assertTrue(hot.contains(BatteryEvent.PauseForHeat))
        assertEquals(emptyList<BatteryEvent>(), m.update(s(43.0)))
        val cool = m.update(s(41.9))
        assertTrue(cool.contains(BatteryEvent.ResumeAfterHeat))
        assertEquals(TempState.WARNING, m.tempState)
    }

    @Test
    fun ignoresInvalidReadings() {
        val m = BatteryMonitor()
        assertEquals(emptyList<BatteryEvent>(), m.update(s(tempC = 0.0, voltageMv = 0)))
        assertEquals(emptyList<BatteryEvent>(), m.update(s(tempC = -1.0, voltageMv = 0)))
        assertEquals(TempState.NORMAL, m.tempState)
        assertEquals(0.0, m.maxTempC, 0.0)
    }

    @Test
    fun lowVoltageOnceWithReset() {
        val m = BatteryMonitor()
        assertTrue(texts(m.update(s(voltageMv = 3590))).single().startsWith("🪫 Battery voltage LOW"))
        assertEquals(emptyList<BatteryEvent>(), m.update(s(voltageMv = 3595)))
        assertEquals(emptyList<BatteryEvent>(), m.update(s(voltageMv = 3760)))
        assertTrue(texts(m.update(s(voltageMv = 3590))).single().startsWith("🪫 Battery voltage LOW"))
    }

    @Test
    fun chargingStoppedAndResumed() {
        val m = BatteryMonitor()
        m.update(s(charging = true))
        assertTrue(texts(m.update(s(charging = false))).single().startsWith("⚠️ Phone is not charging"))
        assertTrue(texts(m.update(s(charging = true))).single().startsWith("🔌 Charging again"))
    }

    @Test
    fun healthChange() {
        val m = BatteryMonitor()
        m.update(s(health = "good"))
        assertTrue(texts(m.update(s(health = "OVERHEAT"))).single().contains("health: OVERHEAT"))
    }

    @Test
    fun maxTempTracked() {
        val m = BatteryMonitor()
        m.update(s(30.0)); m.update(s(36.0)); m.update(s(33.0))
        assertEquals(36.0, m.maxTempC, 0.0)
        m.resetMax()
        m.update(s(33.0))
        assertEquals(33.0, m.maxTempC, 0.0)
    }
}
