// LateCommandsTest.kt: JVM tests for core/LateCommands. Docs: docs/WORKFLOW.md §2.

package com.nandan.cctv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LateCommandsTest {
    @Test
    fun freshCommandsRun() {
        for (c in listOf("/snapshot", "/clip", "/record", "/testkill")) assertEquals(LateAction.RUN, LateCommands.action(c, 5))
    }

    @Test
    fun lateSettingsRun() {
        for (c in listOf("/record", "/alerts", "/videos")) assertEquals(LateAction.RUN, LateCommands.action(c, 4808))
    }

    @Test
    fun lateReadOnlyRun() {
        for (c in listOf("/status", "/summary", "/last", "/log", "/users", "/allow", "/revoke")) assertEquals(c, LateAction.RUN, LateCommands.action(c, 4808))
    }

    @Test
    fun lateActionsSkipped() {
        for (c in listOf("/snapshot", "/clip", "/sshd", "/testkill", "/testtemp")) assertEquals(c, LateAction.SKIP, LateCommands.action(c, 4808))
    }

    @Test
    fun boundaryIsInclusive() {
        assertEquals(LateAction.RUN, LateCommands.action("/clip", 120))
        assertEquals(LateAction.SKIP, LateCommands.action("/clip", 121))
    }

    @Test
    fun onlySlashWordsAreCommands() {
        assertTrue(LateCommands.isCommand("/status"))
        for (t in listOf("", "/", "hi", "status")) assertFalse(t, LateCommands.isCommand(t))
    }
}
