// AuthTest.kt: JVM tests for core/Auth (owner/member roles, private chats only, JSON). Docs: docs/WORKFLOW.md §2.

package com.nandan.cctv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTest {
    private val owner = 111L
    private val friend = 222L

    @Test
    fun ownerHasRoleInPrivate() {
        assertEquals(Role.OWNER, Auth(owner).roleOf(owner, "private"))
    }

    @Test
    fun groupChatGetsNoRole() {
        val a = Auth(owner)
        for (t in listOf("group", "supergroup", "channel")) assertNull(t, a.roleOf(owner, t))
    }

    @Test
    fun unknownUserGetsNull() {
        assertNull(Auth(owner).roleOf(friend, "private"))
    }

    @Test
    fun allowThenMember() {
        val a = Auth(owner)
        assertTrue(a.allow(friend, "Rahul"))
        assertEquals(Role.MEMBER, a.roleOf(friend, "private"))
        assertFalse(a.allow(friend, "Rahul"))  // already there
        assertFalse(a.allow(owner, "Me"))      // owner can't be added again
        assertEquals(listOf(owner, friend), a.users().map { it.id }) // owner first
        assertTrue(a.revoke(friend))
        assertNull(a.roleOf(friend, "private"))
    }

    @Test
    fun revokeOwnerFails() {
        val a = Auth(owner)
        assertFalse(a.revoke(owner))
        assertEquals(Role.OWNER, a.roleOf(owner, "private"))
    }

    @Test
    fun jsonRoundTrip() {
        val a = Auth(owner)
        a.allow(friend, "Rahul")
        val b = Auth.fromJson(owner, a.toJson())
        assertEquals(a.users(), b.users())
        assertEquals(Role.MEMBER, b.roleOf(friend, "private"))
    }

    @Test
    fun corruptJsonFallsBackToOwnerOnly() {
        for (bad in listOf("{oops", null, "[]", "{\"members\":\"x\"}")) {
            val a = Auth.fromJson(owner, bad)
            assertEquals(listOf(owner), a.users().map { it.id })
            assertNull(a.roleOf(friend, "private"))
        }
    }

    @Test
    fun badEntrySkippedOthersKept() {
        val json = "{\"members\":[{\"id\":222,\"name\":\"A\"},{\"name\":\"no id\"},{\"id\":333,\"name\":\"B\"}]}"
        val a = Auth.fromJson(owner, json)
        assertEquals(listOf(owner, 222L, 333L), a.users().map { it.id })
    }
}
