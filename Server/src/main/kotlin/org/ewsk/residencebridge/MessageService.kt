package org.ewsk.residencebridge

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

internal data class LanguageSnapshot(
    val bundles: Map<String, Map<String, String>>,
    val defaultLocale: String,
    val followClient: Boolean
)

class MessageService private constructor(
    private val plugin: JavaPlugin,
    private val logger: Logger
) {
    private val miniMessage = MiniMessage.miniMessage()
    private val missingKeys = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var snapshot = LanguageSnapshot(emptyMap(), "zh_CN", true)

    fun initialize(config: BridgeConfig) {
        copyBuiltInLanguages()
        LegacyMessageMigration.migrateIfNeeded(plugin.dataFolder, File(plugin.dataFolder, "config.yml"), config.language.defaultLocale)
        snapshot = loadSnapshot(config.language)
    }

    /** 完整加载成功后才替换当前快照；失败时旧语言映射保持可用。 */
    fun reload(language: LanguageConfig) {
        val loaded = loadSnapshot(language)
        snapshot = loaded
        missingKeys.clear()
    }

    fun component(sender: CommandSender?, key: String, placeholders: Map<String, Any?> = emptyMap()): Component {
        val template = resolveTemplate(localeFor(sender), key)
        return renderMessage(miniMessage, template, placeholders)
    }

    fun send(sender: CommandSender, key: String, placeholders: Map<String, Any?> = emptyMap()) {
        sender.sendMessage(component(sender, key, placeholders))
    }

    fun text(sender: CommandSender?, key: String): String = resolveTemplate(localeFor(sender), key)

    internal fun selectedLocale(requested: String?): String = selectLocale(
        requested = requested,
        available = snapshot.bundles.keys,
        defaultLocale = snapshot.defaultLocale
    )

    private fun localeFor(sender: CommandSender?): String? {
        if (sender !is Player || !snapshot.followClient) return snapshot.defaultLocale
        return sender.locale().toString()
    }

    private fun resolveTemplate(requested: String?, key: String): String {
        val state = snapshot
        val selected = selectLocale(requested, state.bundles.keys, state.defaultLocale)
        val chain = linkedSetOf(selected, normalizeLocale(state.defaultLocale), "zh_CN")
        chain.forEach { locale -> state.bundles[locale]?.get(key)?.let { return it } }
        if (missingKeys.add(key)) logger.warning("Missing i18n key: $key")
        return "<red>Missing message: $key</red>"
    }

    private fun loadSnapshot(language: LanguageConfig): LanguageSnapshot {
        val langFolder = File(plugin.dataFolder, "lang")
        val bundles = langFolder.listFiles { file -> file.isFile && file.extension.equals("yml", true) }
            .orEmpty()
            .associate { file -> normalizeLocale(file.nameWithoutExtension) to loadBundle(file) }
        require(bundles.isNotEmpty()) { "No language files found in ${langFolder.absolutePath}" }
        return LanguageSnapshot(bundles.toMap(), normalizeLocale(language.defaultLocale), language.followClient)
    }

    private fun loadBundle(file: File): Map<String, String> {
        val yaml = YamlConfiguration()
        yaml.load(file)
        return yaml.getValues(true).mapNotNull { (key, value) ->
            (value as? String)?.let { key to it }
        }.toMap()
    }

    private fun copyBuiltInLanguages() {
        File(plugin.dataFolder, "lang").mkdirs()
        listOf("zh_CN.yml", "en_US.yml").forEach { name ->
            val target = File(plugin.dataFolder, "lang/$name")
            if (!target.exists()) {
                plugin.saveResource("lang/$name", false)
            } else {
                mergeMissingBuiltInKeys(name, target)
            }
        }
    }

    private fun mergeMissingBuiltInKeys(resourceName: String, target: File) {
        val defaults = plugin.getResource("lang/$resourceName")?.bufferedReader(Charsets.UTF_8)?.use {
            YamlConfiguration.loadConfiguration(it)
        } ?: return
        val current = YamlConfiguration.loadConfiguration(target)
        var changed = false
        defaults.getValues(true).forEach { (key, value) ->
            if (value is String && !current.contains(key)) {
                current.set(key, value)
                changed = true
            }
        }
        if (changed) current.save(target)
    }

    companion object {
        fun create(plugin: JavaPlugin): MessageService = MessageService(plugin, plugin.logger)
    }
}

internal fun renderMessage(miniMessage: MiniMessage, template: String, values: Map<String, Any?>): Component {
    val resolvers = values.map { (name, value) ->
        TagResolver.resolver(name, Tag.inserting(Component.text(value?.toString().orEmpty())))
    }
    return miniMessage.deserialize(MessageUtil.legacyToMiniMessage(template), TagResolver.resolver(resolvers))
}

internal fun normalizeLocale(locale: String): String {
    val parts = locale.trim().replace('-', '_').split('_').filter { it.isNotBlank() }
    if (parts.isEmpty()) return ""
    val language = parts[0].lowercase(Locale.ROOT)
    return if (parts.size == 1) language else "${language}_${parts[1].uppercase(Locale.ROOT)}"
}

internal fun selectLocale(requested: String?, available: Set<String>, defaultLocale: String): String {
    val normalizedAvailable = available.associateBy(::normalizeLocale)
    val exact = normalizeLocale(requested.orEmpty())
    normalizedAvailable[exact]?.let { return normalizeLocale(it) }
    val language = exact.substringBefore('_')
    if (language.isNotEmpty()) {
        normalizedAvailable.keys.sorted().firstOrNull { it.substringBefore('_') == language }?.let { return it }
    }
    val fallback = normalizeLocale(defaultLocale)
    normalizedAvailable[fallback]?.let { return normalizeLocale(it) }
    normalizedAvailable["zh_CN"]?.let { return "zh_CN" }
    return normalizedAvailable.keys.sorted().firstOrNull().orEmpty()
}

internal object LegacyMessageMigration {
    const val MARKER_NAME = ".messages-migrated-v1"
    private val mappings = mapOf(
        "list.header" to "list.header",
        "list.other-header" to "list.other-header",
        "list.line" to "list.line",
        "list.empty" to "list.empty",
        "messages.duplicate" to "residence.duplicate",
        "messages.not-found" to "residence.not-found",
        "messages.switching" to "teleport.switching",
        "messages.local-teleport-failed" to "teleport.local-failed",
        "messages.connect-request-failed" to "teleport.connect-failed",
        "messages.limit-reached" to "residence.limit-reached",
        "messages.teleport-wait" to "teleport.wait",
        "messages.teleport-cancelled" to "teleport.cancelled",
        "messages.remote-action-switching" to "remote.switching",
        "messages.remote-action-queued" to "remote.queued",
        "messages.no-permission" to "command.no-permission"
    )

    fun migrateIfNeeded(dataFolder: File, configFile: File, defaultLocale: String): Boolean {
        val marker = File(dataFolder, MARKER_NAME)
        if (marker.exists()) return false
        val target = File(dataFolder, "lang/${normalizeLocale(defaultLocale)}.yml")
        if (configFile.isFile && target.isFile) {
            val oldConfig = YamlConfiguration.loadConfiguration(configFile)
            val language = YamlConfiguration.loadConfiguration(target)
            mappings.forEach { (oldKey, newKey) ->
                oldConfig.getString(oldKey)?.let { language.set(newKey, MessageUtil.legacyToMiniMessage(it)) }
            }
            language.save(target)
        }
        marker.parentFile?.mkdirs()
        marker.createNewFile()
        return true
    }
}
