package com.example.pinch

import com.example.pinch.sync.GroupInvitation
import com.example.pinch.model.Recipe
import com.example.pinch.model.RecipeOrigin
import com.example.pinch.data.PinchJson
import org.junit.Assert.*
import org.junit.Test

class GroupTest {
    @Test fun invitationsCarryServerPathAndRejectUnsafeAddresses() {
        val invitation = GroupInvitation.parse("https://recipes.example.com/pinch/join#invite-code")
        assertEquals("https://recipes.example.com/pinch", invitation.server)
        assertEquals("invite-code", invitation.code)
        assertEquals(invitation, GroupInvitation.parse("pinch://join?server=https%3A%2F%2Frecipes.example.com%2Fpinch&code=invite-code"))
        for (link in listOf("http://recipes.example.com/join#code", "https://user:password@example.com/join#code", "https://example.com/join?secret=x#code", "pinch://join?server=http%3A%2F%2Fexample.com&code=code", "https://example.com/join#../invalid", "https://example.com/join")) {
            assertThrows(Exception::class.java) { GroupInvitation.parse(link) }
        }
    }
    @Test fun savedCopyAttributionSurvivesDiskAndSyncEncoding() {
        val recipe = Recipe(title = "Family bread", origin = RecipeOrigin("group-1", "share-1", "Family", "alex"))
        assertEquals(recipe.origin, PinchJson.decode(PinchJson.encode(recipe)).origin)
        assertNull(PinchJson.decode(PinchJson.encode(recipe.copy(origin = null))).origin)
    }
}
