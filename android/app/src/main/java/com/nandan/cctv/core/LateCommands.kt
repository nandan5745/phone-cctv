// LateCommands.kt: core (no android.*): which commands still run when they arrive late; used by Bot.
// Tests: LateCommandsTest. Docs: docs/ARCHITECTURE.md §7, docs/INCIDENTS.md S9.

package com.nandan.cctv.core

enum class LateAction { RUN, SKIP }

/**
 * What to do with a command that reaches the phone late (e.g. sent while it was offline).
 * Settings and read-only commands still run, so "/record off" isn't lost; commands that
 * act on the moment (photo, clip, SSH, tests) are skipped, and the user is told.
 */
object LateCommands {
    val RUN_WHEN_LATE = setOf("/record", "/alerts", "/videos", "/status", "/help", "/start", "/log", "/last", "/reload", "/summary")

    fun action(name: String, ageS: Long, maxAgeS: Long = 120): LateAction =
        if (ageS <= maxAgeS || name in RUN_WHEN_LATE) LateAction.RUN else LateAction.SKIP
}
