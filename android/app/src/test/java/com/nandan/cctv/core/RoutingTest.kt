// RoutingTest.kt: JVM tests for core/Routing (who gets an answer, who gets ignored). Docs: docs/WORKFLOW.md §2.

package com.nandan.cctv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class RoutingTest {
    @Test
    fun groupChatIgnoredEvenForOwner() {
        for (t in listOf("group", "supergroup", "channel")) {
            assertEquals(t, Route.IGNORE_NOT_PRIVATE, Routing.route(null, t, "/status"))
        }
    }

    @Test
    fun strangerInPrivateChat() {
        assertEquals(Route.STRANGER, Routing.route(null, "private", "/status"))
    }

    @Test
    fun memberOwnerCommandNotAllowed() {
        assertEquals(Route.NOT_ALLOWED, Routing.route(Role.MEMBER, "private", "/record"))
        assertEquals(Route.NOT_ALLOWED, Routing.route(Role.MEMBER, "private", "/allow"))
    }

    @Test
    fun memberCommandAndOwnerRun() {
        assertEquals(Route.RUN, Routing.route(Role.MEMBER, "private", "/snapshot"))
        assertEquals(Route.RUN, Routing.route(Role.OWNER, "private", "/allow"))
    }

    @Test
    fun strangerNoticeNamesAndIdAndIsShort() {
        val n = Routing.strangerNotice("Rahul", 222)
        assertEquals("👤 Unknown user Rahul (id 222) wrote to the bot. /allow 222 to add them.", n)
        val long = Routing.strangerNotice("x".repeat(500), 5)
        assertTrue(long.length < 150)
        assertTrue(Routing.strangerNotice(null, 7).contains("(id 7)"))
    }

    @Test
    fun ownerNotRateLimitedMembersAre() {
        assertFalse(Routing.rateLimited(Role.OWNER))
        assertTrue(Routing.rateLimited(Role.MEMBER))
    }

    @Test
    fun memberUnknownCommandIsUnknownNotOwnerOnly() {
        assertEquals(Route.UNKNOWN, Routing.route(Role.MEMBER, "private", "/snapshop"))
        assertEquals(Route.UNKNOWN, Routing.route(Role.OWNER, "private", "/stats"))
        assertEquals(Route.NOT_ALLOWED, Routing.route(Role.MEMBER, "private", "/log"))
    }

    @Test
    fun memberStatusHidesNetworkLine() {
        val full = "📊 CCTV status\nUp: 1m\nWi-Fi: Home, IP 203.0.113.9, -49 dBm (4/4)\nCamera: 640x480"
        val m = Routing.memberStatus(full)
        assertFalse(m.contains("Wi-Fi"))
        assertFalse(m.contains("203.0.113.9"))
        assertTrue(m.contains("Camera: 640x480"))
    }

    @Test
    fun strangerNoticesOncePerStrangerAndFiveAnHour() {
        val n = StrangerNotices()
        assertTrue(n.shouldNotify(1, 0))
        assertFalse(n.shouldNotify(1, 60_000))                       // same stranger, same day
        for (id in 2L..5L) assertTrue(n.shouldNotify(id, 1_000))      // 5 in this hour
        assertFalse(n.shouldNotify(6, 2_000))                        // 6th stranger in the hour: no notice
        assertTrue(n.shouldNotify(7, 3_600_001))                     // next hour
    }
}
