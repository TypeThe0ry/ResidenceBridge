package org.ewsk.residencebridge

import kotlin.test.Test
import kotlin.test.assertEquals

class CompletionMergeTest {

    @Test
    fun `keeps native sub-residences while adding cross-server residences`() {
        val nativeSuggestions = listOf("parent.child", "parent.child.grandchild")
        val bridgeSuggestions = listOf("parent", "remote")

        assertEquals(
            listOf("parent", "parent.child", "parent.child.grandchild", "remote"),
            combineTabCompletions("remove", nativeSuggestions, bridgeSuggestions)
        )
    }

    @Test
    fun `deduplicates names case-insensitively and preserves native casing`() {
        val nativeSuggestions = listOf("Parent.Child", "Local")
        val bridgeSuggestions = listOf("parent.child", "LOCAL", "Remote")

        assertEquals(
            listOf("Local", "Parent.Child", "Remote"),
            combineTabCompletions("remove", nativeSuggestions, bridgeSuggestions)
        )
    }

    @Test
    fun `keeps list completion bridge-only to preserve its permission boundary`() {
        val nativeSuggestions = listOf("anotherPlayer", "world")
        val bridgeSuggestions = listOf("permittedOwner")

        assertEquals(
            listOf("permittedOwner"),
            combineTabCompletions("list", nativeSuggestions, bridgeSuggestions)
        )
    }

    @Test
    fun `keeps unrelated command completion behavior unchanged`() {
        val nativeSuggestions = listOf("parent.child")
        val bridgeSuggestions = listOf("parent", "remote")

        assertEquals(
            listOf("parent", "remote"),
            combineTabCompletions("rename", nativeSuggestions, bridgeSuggestions)
        )
    }

    @Test
    fun `keeps delete alias bridge-only because Residence does not normalize its completion`() {
        val nativeSuggestions = listOf("remove", "removeall")
        val bridgeSuggestions = listOf("parent", "remote")

        assertEquals(
            listOf("parent", "remote"),
            combineTabCompletions("delete", nativeSuggestions, bridgeSuggestions)
        )
    }
}
