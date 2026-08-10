package org.ewsk.residencebridge

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyMessageMigrationTest {
    @Test
    fun migratesLegacyValuesOnlyOnce() {
        val folder = createTempDirectory("residencebridge-migration").toFile()
        try {
            val langFolder = File(folder, "lang").apply { mkdirs() }
            val config = File(folder, "config.yml").apply {
                writeText("messages:\n  duplicate: '&cCustom %name%'\n")
            }
            val language = File(langFolder, "zh_CN.yml").apply {
                writeText("residence:\n  duplicate: '<red>Default <name>'\n")
            }

            assertTrue(LegacyMessageMigration.migrateIfNeeded(folder, config, "zh_CN"))
            assertEquals("<red>Custom <name>", YamlConfiguration.loadConfiguration(language).getString("residence.duplicate"))

            config.writeText("messages:\n  duplicate: '&aChanged %name%'\n")
            assertFalse(LegacyMessageMigration.migrateIfNeeded(folder, config, "zh_CN"))
            assertEquals("<red>Custom <name>", YamlConfiguration.loadConfiguration(language).getString("residence.duplicate"))
        } finally {
            folder.deleteRecursively()
        }
    }
}
