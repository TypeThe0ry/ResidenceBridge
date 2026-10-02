package org.ewsk.residencebridge

object MessageUtil {

    private val bracketHex = Regex("(?i)<#([0-9a-f]{6})>")
    private val ampHex = Regex("(?i)&?#([0-9a-f]{6})")
    private const val colorCodes = "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx"

    fun color(message: String): String {
        val withBracketHex = bracketHex.replace(message) { hexColor(it.groupValues[1]) }
        val withAmpHex = ampHex.replace(withBracketHex) { hexColor(it.groupValues[1]) }
        return translateAlternateColorCodes(withAmpHex)
    }

    fun apply(message: String, placeholders: Map<String, Any?>): String {
        var result = message
        placeholders.forEach { (key, value) ->
            result = result.replace("%$key%", value?.toString() ?: "")
        }
        return result
    }

    private fun translateAlternateColorCodes(message: String): String {
        val chars = message.toCharArray()
        for (i in 0 until chars.size - 1) {
            if (chars[i] == '&' && colorCodes.indexOf(chars[i + 1]) > -1) {
                chars[i] = '§'
                chars[i + 1] = chars[i + 1].lowercaseChar()
            }
        }
        return String(chars)
    }

    private fun hexColor(hex: String): String {
        val chars = hex.toCharArray()
        return buildString {
            append('§').append('x')
            chars.forEach { append('§').append(it) }
        }
    }
}