package org.ewsk.residencebridge

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class VelocityMessenger(private val plugin: Plugin, private val config: BridgeConfig) {

    fun register() {
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, config.velocityChannel)
        if (config.fallbackBungeeChannel) {
            plugin.server.messenger.registerOutgoingPluginChannel(plugin, "BungeeCord")
        }
    }

    fun unregister() {
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, config.velocityChannel)
        if (config.fallbackBungeeChannel) {
            plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, "BungeeCord")
        }
    }

    fun requestConnect(player: Player, targetServer: String): Boolean {
        return try {
            player.sendPluginMessage(plugin, config.velocityChannel, targetServer.encodeToByteArray())
            if (config.fallbackBungeeChannel) {
                sendBungeeConnect(player, targetServer)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun sendBungeeConnect(player: Player, targetServer: String) {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeUTF("Connect")
            data.writeUTF(targetServer)
        }
        player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray())
    }
}
