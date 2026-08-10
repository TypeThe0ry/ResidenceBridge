package org.ewsk.residencebridgevelocity

import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import org.slf4j.Logger
import javax.inject.Inject

@Plugin(
    id = "residencebridge-velocity",
    name = "ResidenceBridge-Velocity",
    version = "1.2.4",
    authors = ["29622"]
)
class ResidenceBridgeVelocity @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger
) {

    private val channel = MinecraftChannelIdentifier.from("residencebridge:main")

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        proxy.channelRegistrar.register(channel)
        proxy.eventManager.register(this, PluginMessageEvent::class.java) { messageEvent ->
            handlePluginMessage(messageEvent)
            messageEvent
        }
        logger.info("ResidenceBridge-Velocity enabled.")
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        proxy.channelRegistrar.unregister(channel)
    }

    private fun handlePluginMessage(event: PluginMessageEvent) {
        if (event.identifier != channel) {
            return
        }
        event.result = PluginMessageEvent.ForwardResult.handled()
        val player = event.target as? Player ?: return
        if (event.source !is ServerConnection) {
            return
        }
        val targetServer = parseTargetServer(event.data.decodeToString().trim())
        if (targetServer.isEmpty()) {
            return
        }
        val server = proxy.getServer(targetServer).orElseGet {
            proxy.allServers.firstOrNull { it.serverInfo.name.equals(targetServer, ignoreCase = true) }
        }
        if (server == null) {
            logger.warn("Target server not found: $targetServer")
            return
        }
        player.createConnectionRequest(server).connect().exceptionally {
            logger.warn("Failed to connect ${player.username} to $targetServer: ${it.message}")
            null
        }
    }

    private fun parseTargetServer(payload: String): String {
        if (payload.startsWith("connect|", ignoreCase = true)) {
            return payload.substringAfter('|').trim()
        }
        return payload
    }
}
