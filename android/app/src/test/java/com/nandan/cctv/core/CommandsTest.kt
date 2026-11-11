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
}
