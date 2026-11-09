package com.nandan.cctv.core

import org.junit.Assert.assertEquals
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
    fun lateActionsSkipped() {
        for (c in listOf("/snapshot", "/clip", "/sshd", "/testkill", "/testtemp")) assertEquals(c, LateAction.SKIP, LateCommands.action(c, 4808))
    }

    @Test
    fun boundaryIsInclusive() {
        assertEquals(LateAction.RUN, LateCommands.action("/clip", 120))
        assertEquals(LateAction.SKIP, LateCommands.action("/clip", 121))
    }
}
