// RoutingTest.kt: JVM tests for core/Routing (who gets an answer, who gets ignored). Docs: docs/WORKFLOW.md §2.

package com.nandan.cctv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
