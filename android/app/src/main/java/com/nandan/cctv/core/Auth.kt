// Auth.kt: core (no android.*): who may use the bot. One owner (from config) and members the owner
// approved with /allow; private chats only. Stored as JSON (filesDir/users.json, Task 8).
// Tests: AuthTest. Docs: docs/ROADMAP.md Phase 6, docs/DECISIONS.md (private chats).

package com.nandan.cctv.core

import org.json.JSONArray
import org.json.JSONObject

enum class Role { OWNER, MEMBER }

data class User(val id: Long, val name: String, val role: Role)

class Auth(private val ownerId: Long, members: List<User> = emptyList()) {
    private val members = LinkedHashMap<Long, User>().apply {
        for (m in members) if (m.id != ownerId) put(m.id, m.copy(role = Role.MEMBER))
    }

    /** The user's role, or null for strangers and for any chat that isn't a private one. */
    fun roleOf(userId: Long, chatType: String): Role? {
        if (chatType != "private") return null
        if (userId == ownerId) return Role.OWNER
        return members[userId]?.role
    }

    /** False if [id] is the owner or already a member. */
    fun allow(id: Long, name: String): Boolean {
        if (id == ownerId || id in members) return false
        members[id] = User(id, name, Role.MEMBER)
        return true
    }

    /** False for the owner (can't be removed) and for unknown ids. */
    fun revoke(id: Long): Boolean = id != ownerId && members.remove(id) != null

    /** Owner first, then members in the order they were added. */
    fun users(): List<User> = listOf(User(ownerId, "owner", Role.OWNER)) + members.values

    fun toJson(): String {
        val arr = JSONArray()
        for (m in members.values) arr.put(JSONObject().put("id", m.id).put("name", m.name))
        return JSONObject().put("members", arr).toString()
    }

    companion object {
        /** Missing or corrupt JSON gives owner-only access: never "nobody", never "everybody". */
        fun fromJson(ownerId: Long, json: String?): Auth {
            if (json == null) return Auth(ownerId)
            return try {
                val arr = JSONObject(json).optJSONArray("members") ?: return Auth(ownerId)
                // A malformed entry is skipped; it must not cost every other member their access.
                val list = (0 until arr.length()).mapNotNull {
                    val o = arr.optJSONObject(it) ?: return@mapNotNull null
                    if (!o.has("id")) return@mapNotNull null
                    val id = o.optLong("id", 0L).takeIf { v -> v != 0L } ?: return@mapNotNull null
                    User(id, o.optString("name", "?"), Role.MEMBER)
                }
                Auth(ownerId, list)
            } catch (e: Exception) {
                Auth(ownerId)
            }
        }
    }
}
