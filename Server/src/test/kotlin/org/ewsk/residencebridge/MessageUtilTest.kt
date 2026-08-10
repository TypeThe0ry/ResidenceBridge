package org.ewsk.residencebridge

import kotlin.test.Test
import kotlin.test.assertEquals

class MessageUtilTest {
    @Test
    fun convertsLegacyColorsHexAndPlaceholdersToMiniMessage() {
        assertEquals(
            "<red>Hello <name> <#66ccff>world<reset>",
            MessageUtil.legacyToMiniMessage("&cHello %name% &#66ccffworld&r")
        )
    }

    @Test
    fun keepsModernMiniMessageTags() {
        assertEquals(
            "<gradient:red:blue>Hello</gradient> <name>",
            MessageUtil.legacyToMiniMessage("<gradient:red:blue>Hello</gradient> <name>")
        )
    }

    @Test
    fun selectsExactLanguageThenLanguageVariantThenDefault() {
        val available = setOf("zh_CN", "en_US")
        assertEquals("en_US", selectLocale("en-US", available, "zh_CN"))
        assertEquals("en_US", selectLocale("en_GB", available, "zh_CN"))
        assertEquals("zh_CN", selectLocale("fr_FR", available, "zh_CN"))
    }
}
