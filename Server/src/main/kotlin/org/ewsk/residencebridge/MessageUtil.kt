package org.ewsk.residencebridge

/** 仅用于读取旧配置和兼容粘贴到语言文件中的旧式颜色代码。 */
object MessageUtil {
    private val bracketHex = Regex("(?i)<#([0-9a-f]{6})>")
    private val ampHex = Regex("(?i)&#([0-9a-f]{6})")
    private val legacyCode = Regex("(?i)&([0-9a-fk-or])")
    private val legacyPlaceholder = Regex("%([A-Za-z0-9_]+)%")
    private val legacyTags = mapOf(
        '0' to "black", '1' to "dark_blue", '2' to "dark_green", '3' to "dark_aqua",
        '4' to "dark_red", '5' to "dark_purple", '6' to "gold", '7' to "gray",
        '8' to "dark_gray", '9' to "blue", 'a' to "green", 'b' to "aqua",
        'c' to "red", 'd' to "light_purple", 'e' to "yellow", 'f' to "white",
        'k' to "obfuscated", 'l' to "bold", 'm' to "strikethrough", 'n' to "underlined",
        'o' to "italic", 'r' to "reset"
    )

    fun legacyToMiniMessage(message: String): String {
        val normalizedHex = ampHex.replace(bracketHex.replace(message) { "<#${it.groupValues[1]}>" }) {
            "<#${it.groupValues[1]}>"
        }
        val withTags = legacyCode.replace(normalizedHex) {
            val tag = legacyTags.getValue(it.groupValues[1].lowercase()[0])
            "<$tag>"
        }
        return legacyPlaceholder.replace(withTags) { "<${it.groupValues[1]}>" }
    }
}
