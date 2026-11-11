// NetDiagTest.kt: JVM tests for core/NetDiag (IP formatting, outage cause). Docs: docs/WORKFLOW.md §2.

package com.nandan.cctv.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NetDiagTest {
    @Test
    fun formatsLittleEndianIp() {
        // 203.0.113.9 is a documentation-only address (RFC 5737).
        assertEquals("203.0.113.9", formatIpv4(0x097100CB))
    }

    @Test
    fun formatsHighBytes() {
        // Bytes above 127 must not come out negative.
        assertEquals("198.51.100.255", formatIpv4(0xFF6433C6.toInt()))
    }

    @Test
    fun classifyWifiDown() {
        assertEquals(OutageCause.WIFI_DOWN, classify(false, 204))
        assertEquals(OutageCause.WIFI_DOWN, classify(false, null))
    }

    @Test
    fun classifyPortal() {
        assertEquals(OutageCause.CAPTIVE_PORTAL, classify(true, 200))
        assertEquals(OutageCause.CAPTIVE_PORTAL, classify(true, 302))
    }

    @Test
    fun classifyNoInternet() {
        assertEquals(OutageCause.NO_INTERNET, classify(true, null))
    }

    @Test
    fun classifyTelegramDown() {
        assertEquals(OutageCause.TELEGRAM_DOWN, classify(true, 204))
    }
}
