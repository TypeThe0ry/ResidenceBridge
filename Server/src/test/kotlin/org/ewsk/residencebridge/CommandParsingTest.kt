package org.ewsk.residencebridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 命令预判与分词的行为测试。
 *
 * isResidenceCommandMessage 是每条玩家命令都会经过的快速前置判断，
 * splitArguments 取代了原先每次调用都重新编译的 Regex("\\s+")，
 * 两者都必须与原实现保持完全一致的语义。
 */
class CommandParsingTest {

    // ===== isResidenceCommandMessage =====

    @Test
    fun `accepts all residence roots regardless of case`() {
        listOf("res", "residence", "resadmin", "residenceadmin").forEach { root ->
            assertTrue(isResidenceCommandMessage("/$root tp home"), root)
            assertTrue(isResidenceCommandMessage("/${root.uppercase()} tp home"), root.uppercase())
        }
    }

    @Test
    fun `accepts messages without leading slash`() {
        assertTrue(isResidenceCommandMessage("res tp home"))
    }

    @Test
    fun `rejects non residence commands`() {
        assertFalse(isResidenceCommandMessage("/spawn"))
        assertFalse(isResidenceCommandMessage("/home set base"))
        assertFalse(isResidenceCommandMessage("/msg res tp"))
    }

    @Test
    fun `rejects prefixes that merely start with a residence root`() {
        // 必须整段 token 匹配，不能只匹配前缀，否则会误吞 /reset、/residences 之类的命令
        assertFalse(isResidenceCommandMessage("/reset password"))
        assertFalse(isResidenceCommandMessage("/residences list"))
        assertFalse(isResidenceCommandMessage("/resadminx remove a"))
    }

    @Test
    fun `rejects root without a sub command`() {
        assertFalse(isResidenceCommandMessage("/res"))
        assertFalse(isResidenceCommandMessage("/res "))
        assertFalse(isResidenceCommandMessage("/res    "))
    }

    @Test
    fun `handles blank and slash only input`() {
        assertFalse(isResidenceCommandMessage(""))
        assertFalse(isResidenceCommandMessage("/"))
        assertFalse(isResidenceCommandMessage("   "))
    }

    @Test
    fun `tolerates extra whitespace around tokens`() {
        assertTrue(isResidenceCommandMessage("/  res   tp   home"))
        assertTrue(isResidenceCommandMessage("/res\ttp\thome"))
    }

    // ===== splitArguments =====

    @Test
    fun `splits on runs of whitespace exactly like the previous regex`() {
        val samples = listOf(
            "res tp home",
            "res   tp    home",
            "res\ttp\t\thome",
            "  res tp home  ",
            "",
            "   ",
            "single",
            "res tp my home name"
        )
        samples.forEach { sample ->
            val expected = sample.split(Regex("\\s+")).filter { it.isNotEmpty() }
            assertEquals(expected, splitArguments(sample), "sample=<$sample>")
        }
    }

    @Test
    fun `returns empty list for blank input`() {
        assertEquals(emptyList(), splitArguments(""))
        assertEquals(emptyList(), splitArguments("    "))
    }
}
