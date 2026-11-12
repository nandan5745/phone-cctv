// CommandsTest.kt: JVM tests for core/Commands (parsing, who may use what). Docs: docs/WORKFLOW.md §2.

package com.nandan.cctv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {
    @Test
    fun parseStripsBotSuffixAndLowercases() {
        assertEquals(Command("/clip", "5", "5"), Commands.parse("/Clip@cctv_bot 5"))
    }

    @Test
    fun parseKeepsRestForSpeak() {
        assertEquals("hello there", Commands.parse("/speak hello there")!!.rest)
        assertEquals(Command("/status", null, ""), Commands.parse("  /status  "))
    }

    @Test
    fun parseReturnsNullForEmptyOrNonCommand() {
        for (t in listOf(null, "", "hello", "/")) assertNull("$t", Commands.parse(t))
    }

    @Test
    fun memberCannotUseOwnerCommands() {
        for (c in listOf("/record", "/testkill", "/teststop", "/testtemp", "/sshd", "/allow", "/speak", "/summary")) {
            assertFalse(c, Commands.allowed(Role.MEMBER, c))
            assertTrue(c, Commands.allowed(Role.OWNER, c))
        }
    }

    @Test
    fun memberCanUseMemberCommands() {
        for (c in Commands.MEMBER) assertTrue(c, Commands.allowed(Role.MEMBER, c))
    }

    @Test
    fun sshdOnDefault30() {
        assertEquals(30, Commands.sshdMinutes("on", "on"))
        assertEquals(30, Commands.sshdMinutes(null, ""))       // plain /sshd = on for 30 min
    }

    @Test
    fun sshdOnClamped() {
        assertEquals(120, Commands.sshdMinutes("on", "on 500"))
        assertEquals(5, Commands.sshdMinutes("on", "on 1"))
        assertEquals(45, Commands.sshdMinutes("on", "on 45"))
    }

    @Test
    fun sshdOffIsNull() {
        assertNull(Commands.sshdMinutes("off", "off"))
    }

    @Test
    fun sshdGarbageDefaults30() {
        assertEquals(30, Commands.sshdMinutes("on", "on soon"))
        assertEquals(30, Commands.sshdMinutes("maybe", "maybe"))
    }

    @Test
    fun sshdNumberWithoutOn() {
        assertEquals(10, Commands.sshdMinutes("10", "10"))
        assertEquals(120, Commands.sshdMinutes("999", "999"))
    }

    @Test
    fun sshdDeadlineExpiry() {
        val min = 60_000L
        assertFalse(Commands.sshdExpired(nowMs = 0, untilMs = 0))              // not on
        assertFalse(Commands.sshdExpired(nowMs = 0, untilMs = 30 * min))       // on, 30 min left
        assertTrue(Commands.sshdExpired(nowMs = 30 * min, untilMs = 30 * min)) // deadline reached
        // Clock moved back: a deadline more than 120 min away can't be real.
        assertTrue(Commands.sshdExpired(nowMs = 0, untilMs = 121 * min))
    }
}
