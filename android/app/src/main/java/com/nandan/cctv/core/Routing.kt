// Routing.kt: core (no android.*): what the bot does with a message, given the sender's role, the chat
// type and the command; the owner notice for strangers. Used by Bot.handle. Tests: RoutingTest.

package com.nandan.cctv.core

enum class Route { IGNORE_NOT_PRIVATE, STRANGER, UNKNOWN, NOT_ALLOWED, RUN }

object Routing {
    /** [role] from Auth.roleOf (null = stranger, or any non-private chat). */
    fun route(role: Role?, chatType: String, name: String): Route = when {
        chatType != "private" -> Route.IGNORE_NOT_PRIVATE
        role == null -> Route.STRANGER
        name !in Commands.KNOWN -> Route.UNKNOWN
        !Commands.allowed(role, name) -> Route.NOT_ALLOWED
        else -> Route.RUN
    }

    /** The 10-a-minute limit is for members; the owner's backlog after an outage must all run (S9). */
    fun rateLimited(role: Role): Boolean = role != Role.OWNER

    /** /status for a member: without the Wi-Fi line (network name and the phone's IP address). */
    fun memberStatus(status: String): String = status.lines().filterNot { it.startsWith("Wi-Fi:") }.joinToString("\n")

    /** Sent to the owner (at most once a day per stranger); the name is cut short. */
    fun strangerNotice(firstName: String?, id: Long): String {
        val name = (firstName ?: "").trim().take(40).ifEmpty { "(no name)" }
        return "👤 Unknown user $name (id $id) wrote to the bot. /allow $id to add them."
    }
}

/** Stranger notices to the owner: once a day per stranger, and at most [maxPerHour] in total (many throwaway accounts). */
class StrangerNotices(maxPerHour: Int = 5) {
    private val perStranger = RateLimiter(1, 24 * 3_600_000L)
    private val total = RateLimiter(maxPerHour, 3_600_000L)

    fun shouldNotify(id: Long, nowMs: Long): Boolean = perStranger.tryAcquire(id, nowMs) && total.tryAcquire(0, nowMs)
}
