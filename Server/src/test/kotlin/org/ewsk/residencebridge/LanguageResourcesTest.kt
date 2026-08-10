package org.ewsk.residencebridge

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader
import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageResourcesTest {
    @Test
    fun builtInLanguagesContainTheSameMessageKeys() {
        assertEquals(keys("lang/zh_CN.yml"), keys("lang/en_US.yml"))
    }

    @Test
    fun insertedPluginValuesAreNotParsedAsMiniMessage() {
        val component = renderMessage(
            MiniMessage.miniMessage(),
            "<green>Residence: <name>",
            mapOf("name" to "<red>unsafe</red>")
        )
        assertEquals("Residence: <red>unsafe</red>", PlainTextComponentSerializer.plainText().serialize(component))
    }

    private fun keys(resource: String): Set<String> {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(resource))
        val yaml = stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }
        return yaml.getValues(true).filterValues { it is String }.keys
    }
}
