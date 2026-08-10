package org.ewsk.residencebridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ResidenceRoutingTest {

    @Test
    fun `looks up the full sub-residence before its ancestors`() {
        val lookups = mutableListOf<String>()

        val route = findResidenceRoute("A.B.C") { candidate ->
            lookups += candidate
            candidate.takeIf { it == "A" }
        }

        assertEquals("A", route)
        assertEquals(listOf("A.B.C", "A.B", "A"), lookups)
    }

    @Test
    fun `prefers an exact remote sub-residence over a local parent route`() {
        val exact = indexEntry("A.B", "survival-2")
        val parent = indexEntry("A", "survival-1")
        val lookups = mutableListOf<String>()

        val route = findResidenceIndexRoute("A.B") { candidate ->
            lookups += candidate
            when (candidate) {
                "A.B" -> exact
                "A" -> parent
                else -> null
            }
        }

        assertSame(exact, route)
        assertEquals(listOf("A.B"), lookups)
    }

    @Test
    fun `does not invent a fallback for a normal residence`() {
        val lookups = mutableListOf<String>()

        val route = findResidenceRoute("A") { candidate ->
            lookups += candidate
            null
        }

        assertNull(route)
        assertEquals(listOf("A"), lookups)
    }

    @Test
    fun `resolves nested sub-residences when direct api lookup is unavailable`() {
        data class ResidenceNode(val children: Map<String, ResidenceNode> = emptyMap())

        val child = ResidenceNode()
        val parent = ResidenceNode(mapOf("b" to ResidenceNode(mapOf("c" to child))))

        val residence = findResidenceByPath(
            "A.B.C",
            rootLookup = { name -> parent.takeIf { name.equals("a", ignoreCase = true) } },
            childLookup = { node, name -> node.children[name.lowercase()] }
        )

        assertSame(child, residence)
    }

    @Test
    fun `keeps the complete target name when routing through a parent`() {
        val parent = indexEntry("A", "survival-2")

        val target = findResidenceIndexRoute("A.B.C") { candidate -> parent.takeIf { candidate == "A" } }

        requireNotNull(target)
        assertEquals("a.b.c", target.nameKey)
        assertEquals("A.B.C", target.displayName)
        assertEquals("survival-2", target.serverId)
    }

    private fun indexEntry(name: String, serverId: String) = ResidenceIndexEntry(
        nameKey = key(name),
        displayName = name,
        serverId = serverId,
        worldName = "world",
        ownerUuid = null,
        ownerName = "Player",
        updatedAt = 1L
    )
}
