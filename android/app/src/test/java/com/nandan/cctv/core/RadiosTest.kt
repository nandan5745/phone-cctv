// RadiosTest.kt: JVM tests for core/Radios (the /status radio line). Docs: docs/WORKFLOW.md §2.

package com.nandan.cctv.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RadiosTest {
    @Test
    fun bluetoothStates() {
        assertEquals("Radios: Bluetooth off", Radios.line(bluetoothOn = false))
        assertEquals("Radios: Bluetooth ON (turn it off: docs/SECURITY.md)", Radios.line(bluetoothOn = true))
        assertEquals("Radios: Bluetooth unknown", Radios.line(bluetoothOn = null))
    }
}
