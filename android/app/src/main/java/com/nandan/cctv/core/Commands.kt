// Commands.kt: core (no android.*): parse a Telegram message into a command and decide which role may
// use it (members: viewing commands only). Used by Bot (Task 8). Tests: CommandsTest.

package com.nandan.cctv.core

import java.util.Locale

/** [name] lowercased without "@botname"; [arg] = first word after it, lowercased; [rest] = everything after it, as typed. */
data class Command(val name: String, val arg: String?, val rest: String)

object Commands {
    /** What a member (roommate) may use; everything else is owner-only. */
    val MEMBER = setOf("/start", "/help", "/snapshot", "/clip", "/last", "/status")

    /** Every command the bot knows; anything else gets "Unknown command" (also from members). */
    val KNOWN = MEMBER + setOf(
        "/record", "/alerts", "/videos", "/log", "/sshd", "/summary", "/reload",
        "/allow", "/revoke", "/users", "/testkill", "/teststop", "/testtemp",
    )

    fun parse(text: String?): Command? {
        val t = text?.trim() ?: return null
        if (!t.startsWith("/")) return null
        val first = t.split(Regex("\\s+"), limit = 2)
        val name = first[0].substringBefore('@').lowercase(Locale.US)
        if (name.length < 2) return null
        val rest = first.getOrNull(1)?.trim() ?: ""
        val arg = rest.split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.US)
        return Command(name, arg, rest)
    }

    fun allowed(role: Role, name: String): Boolean = role == Role.OWNER || name in MEMBER
}
