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

    /**
     * 请求把玩家转到目标服务器。
     *
     * 两条通道是「主用 + 备用」关系，不能同时发：装了 ResidenceBridge-Velocity 时
     * 自有通道已经会触发一次连接，再补一条 BungeeCord Connect 就变成连续两次连接请求
     * （玩家会看到二次切服闪烁）。所以只有自有通道发送失败时才回落到 BungeeCord。
     */
    fun requestConnect(player: Player, targetServer: String): Boolean {
        val sent = try {
            player.sendPluginMessage(plugin, config.velocityChannel, "connect|$targetServer".encodeToByteArray())
            true
        } catch (t: Throwable) {
            plugin.logger.warning("Failed to send connect request on ${config.velocityChannel}: ${t.message}")
            false
        }
        if (sent) {
            return true
        }
        if (!config.fallbackBungeeChannel) {
            return false
        }
        return try {
            sendBungeeConnect(player, targetServer)
            true
        } catch (t: Throwable) {
            plugin.logger.warning("Failed to send BungeeCord connect fallback: ${t.message}")
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
