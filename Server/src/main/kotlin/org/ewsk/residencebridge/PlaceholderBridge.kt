package org.ewsk.residencebridge

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

object PlaceholderBridge {

    private var expansion: Any? = null

    fun register(plugin: Plugin, config: BridgeConfig, database: BridgeDatabase) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return
        }
        try {
            val created = ResidencePlaceholderExpansion(config, database)
            created.register()
            expansion = created
            plugin.logger.info("PlaceholderAPI placeholders registered.")
        } catch (t: Throwable) {
            expansion = null
        }
    }

    fun unregister() {
        try {
            expansion?.javaClass?.getMethod("unregister")?.invoke(expansion)
        } catch (_: Throwable) {
        } finally {
            expansion = null
        }
    }
}
