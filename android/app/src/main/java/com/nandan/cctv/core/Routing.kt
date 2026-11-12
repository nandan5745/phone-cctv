// Routing.kt: core (no android.*): what the bot does with a message, given the sender's role, the chat
// type and the command; the owner notice for strangers. Used by Bot.handle. Tests: RoutingTest.

package com.nandan.cctv.core

enum class Route { IGNORE_NOT_PRIVATE, STRANGER, NOT_ALLOWED, RUN }

object Routing {
    /** [role] from Auth.roleOf (null = stranger, or any non-private chat). */
    fun route(role: Role?, chatType: String, name: String): Route = when {
        chatType != "private" -> Route.IGNORE_NOT_PRIVATE
        role == null -> Route.STRANGER
        !Commands.allowed(role, name) -> Route.NOT_ALLOWED
        else -> Route.RUN
    }

    /** Sent to the owner (at most once a day per stranger); the name is cut short. */
    fun strangerNotice(firstName: String?, id: Long): String {
        val name = (firstName ?: "").trim().take(40).ifEmpty { "(no name)" }
        return "👤 Unknown user $name (id $id) wrote to the bot. /allow $id to add them."
    }
}
