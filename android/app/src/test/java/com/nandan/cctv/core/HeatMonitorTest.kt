package com.nandan.cctv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatMonitorTest {
    private fun cpu() = HeatMonitor("Phone CPU", warnC = 70.0, criticalC = 80.0, emergencyC = 85.0, resumeC = 75.0, maxValidC = 150.0)
    private fun texts(e: List<BatteryEvent>) = e.filterIsInstance<BatteryEvent.Notify>().map { it.text }

    @Test
    fun normalCpuHeatIsQuiet() {
        val m = cpu()
        assertEquals(emptyList<BatteryEvent>(), m.update(53.6))
        assertEquals(TempState.NORMAL, m.state)
    }

    @Test
    fun labelInMessages() {
        val m = cpu()
        assertTrue(texts(m.update(70.0)).single().startsWith("🌡️ Phone CPU warm"))
        assertTrue(texts(m.update(80.0)).single().startsWith("🔥 Phone CPU HOT"))
    }

    @Test
    fun cpuEmergencyPausesAndResumesBelow75() {
        val m = cpu()
        assertTrue(m.update(85.0).contains(BatteryEvent.PauseForHeat))
        assertEquals(emptyList<BatteryEvent>(), m.update(76.0))
        assertTrue(m.update(74.9).contains(BatteryEvent.ResumeAfterHeat))
    }

    @Test
    fun readingsAboveMaxValidIgnored() {
        val m = cpu()
        assertEquals(emptyList<BatteryEvent>(), m.update(200.0))
        assertEquals(0.0, m.maxC, 0.0)
    }

    @Test
    fun maxTracksAndResets() {
        val m = cpu()
        m.update(55.0); m.update(61.5); m.update(58.0)
        assertEquals(61.5, m.maxC, 0.0)
        m.resetMax(); m.update(54.0)
        assertEquals(54.0, m.maxC, 0.0)
    }
}
