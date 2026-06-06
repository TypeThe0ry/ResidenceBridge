package org.ewsk.residencebridge

import org.bukkit.ChatColor

object MessageUtil {

    private val bracketHex = Regex("(?i)<#([0-9a-f]{6})>")
    private val ampHex = Regex("(?i)&?#([0-9a-f]{6})")

    fun color(message: String): String {
        val withBracketHex = bracketHex.replace(message) { hexColor(it.groupValues[1]) }
        val withAmpHex = ampHex.replace(withBracketHex) { hexColor(it.groupValues[1]) }
        return ChatColor.translateAlternateColorCodes('&', withAmpHex)
    }

    fun apply(message: String, placeholders: Map<String, Any?>): String {
        var result = message
        placeholders.forEach { (key, value) ->
            result = result.replace("%$key%", value?.toString() ?: "")
        }
        return result
    }

    private fun hexColor(hex: String): String {
        val chars = hex.toCharArray()
        return buildString {
            append('§').append('x')
            chars.forEach { append('§').append(it) }
        }
    }
}