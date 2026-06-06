package org.ewsk.residencebridgevelocity

import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import taboolib.common.platform.Plugin
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning
import taboolib.platform.VelocityPlugin

object ResidenceBridgeVelocity : Plugin() {

    private val channel = MinecraftChannelIdentifier.from("residencebridge:main")

    override fun onEnable() {
        val velocity = VelocityPlugin.getInstance()
        velocity.server.channelRegistrar.register(channel)
        info("ResidenceBridge-Velocity enabled.")
    }

    override fun onDisable() {
        val velocity = VelocityPlugin.getInstance()
        velocity.server.channelRegistrar.unregister(channel)
    }

    @SubscribeEvent
    fun onPluginMessage(event: PluginMessageEvent) {
        if (event.identifier != channel) {
            return
        }
        event.result = PluginMessageEvent.ForwardResult.handled()
        val player = event.target as? Player ?: return
        if (event.source !is ServerConnection) {
            return
        }
        val targetServer = event.data.decodeToString().trim()
        if (targetServer.isEmpty()) {
            return
        }
        val server = VelocityPlugin.getInstance().server.getServer(targetServer).orElse(null)
        if (server == null) {
            warning("Target server not found: $targetServer")
            return
        }
        player.createConnectionRequest(server).connect().exceptionally {
            warning("Failed to connect ${player.username} to $targetServer: ${it.message}")
            null
        }
    }
}
