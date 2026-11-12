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

    /**
     * "/sshd on [minutes]" → minutes (default 30, clamped 5..120); "/sshd off" → null.
     * Plain "/sshd" or anything unclear means on for 30 minutes.
     */
    fun sshdMinutes(arg: String?, rest: String): Int? {
        if (arg == "off") return null
        // "/sshd on 10" or "/sshd 10"
        val n = rest.split(Regex("\\s+")).take(2).firstNotNullOfOrNull { it.toIntOrNull() } ?: return 30
        return n.coerceIn(5, 120)
    }

    /**
     * SSH must close: the deadline ([untilMs], 0 = not on) has passed, or it is more than the
     * 120-minute maximum away, which only happens if the clock moved back since /sshd on.
     */
    fun sshdExpired(nowMs: Long, untilMs: Long): Boolean =
        untilMs > 0 && (nowMs >= untilMs || untilMs - nowMs > 120 * 60_000L)

    fun allowed(role: Role, name: String): Boolean = role == Role.OWNER || name in MEMBER
}
