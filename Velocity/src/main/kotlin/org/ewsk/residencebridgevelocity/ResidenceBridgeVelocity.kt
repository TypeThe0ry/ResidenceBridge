package org.ewsk.residencebridgevelocity

import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import com.velocitypowered.api.proxy.server.RegisteredServer
import taboolib.common.platform.Plugin
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning
import taboolib.platform.VelocityPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ResidenceBridgeVelocity : Plugin() {
    private val channel = MinecraftChannelIdentifier.from("residencebridge:main")
    private val connecting = ConcurrentHashMap<UUID, UUID>()

    override fun onEnable() {
        VelocityPlugin.getInstance().server.channelRegistrar.register(channel)
        info("ResidenceBridge-Velocity enabled.")
    }

    override fun onDisable() {
        VelocityPlugin.getInstance().server.channelRegistrar.unregister(channel)
        connecting.clear()
    }

    @SubscribeEvent
    fun onPluginMessage(event: PluginMessageEvent) {
        if (event.identifier != channel) return
        event.result = PluginMessageEvent.ForwardResult.handled()
        val source = event.source as? ServerConnection ?: return
        val player = event.target as? Player ?: return
        if (source.player.uniqueId != player.uniqueId || player.currentServer.orElse(null) !== source) {
            warning("Rejected plugin message: source connection does not match target ${player.username}.")
            return
        }
        when (val request = parseProxyRequest(event.data)) {
            is ProxyRequest.Status -> handleStatus(source, player, request)
            is ProxyRequest.Connect -> handleConnect(source, player, request)
            null -> return
        }
    }

    private fun handleStatus(source: ServerConnection, player: Player, request: ProxyRequest.Status) {
        val server = findServer(request.targetServer)
        if (server == null) {
            respondIfCurrent(source, player, request.requestId, "not-found")
            return
        }
        server.ping().whenComplete { _, error ->
            respondIfCurrent(source, player, request.requestId, if (error == null) "available" else "offline")
        }
    }

    private fun handleConnect(source: ServerConnection, player: Player, request: ProxyRequest.Connect) {
        val server = findServer(request.targetServer)
        if (server == null) {
            request.requestId?.let { respondIfCurrent(source, player, it, "connect-failed") }
            return
        }
        if (source.serverInfo.name.equals(server.serverInfo.name, ignoreCase = true)) {
            request.requestId?.let { respondIfCurrent(source, player, it, "connected") }
            return
        }
        val attempt = UUID.randomUUID()
        if (connecting.putIfAbsent(player.uniqueId, attempt) != null) {
            request.requestId?.let { respondIfCurrent(source, player, it, "connect-failed") }
            return
        }
        player.createConnectionRequest(server).connect().whenComplete { result, error ->
            connecting.remove(player.uniqueId, attempt)
            val connected = error == null && result?.isSuccessful == true
            request.requestId?.let { respondIfCurrent(source, player, it, if (connected) "connected" else "connect-failed") }
            if (!connected) warning("Failed to connect ${player.username} to ${request.targetServer}: ${error?.message ?: result?.status}")
        }
    }

    private fun findServer(name: String): RegisteredServer? {
        val proxy = VelocityPlugin.getInstance().server
        return proxy.getServer(name).orElseGet {
            proxy.allServers.firstOrNull { it.serverInfo.name.equals(name, ignoreCase = true) }
        }
    }

    private fun respondIfCurrent(source: ServerConnection, player: Player, requestId: String, status: String) {
        if (source.player.uniqueId != player.uniqueId || player.currentServer.orElse(null) !== source) return
        if (!source.sendPluginMessage(channel, "result|$requestId|$status".encodeToByteArray())) {
            warning("Failed to return proxy result $status for request $requestId.")
        }
    }
}

internal sealed interface ProxyRequest {
    val targetServer: String
    data class Status(val requestId: String, override val targetServer: String) : ProxyRequest
    data class Connect(val requestId: String?, override val targetServer: String) : ProxyRequest
}

internal const val MAX_PAYLOAD_BYTES = 256
internal const val MAX_SERVER_NAME_LENGTH = 64

internal fun parseProxyRequest(data: ByteArray): ProxyRequest? {
    if (data.isEmpty() || data.size > MAX_PAYLOAD_BYTES) return null
    val payload = data.decodeToString().trim()
    val parts = payload.split('|')
    return when {
        parts.size == 3 && parts[0].equals("status", ignoreCase = true) ->
            ProxyRequest.Status(parts[1].trim(), parts[2].trim()).takeIf {
                it.requestId.isValidToken() && it.targetServer.isValidServerName()
            }
        parts.size == 3 && parts[0].equals("connect", ignoreCase = true) ->
            ProxyRequest.Connect(parts[1].trim(), parts[2].trim()).takeIf {
                it.requestId!!.isValidToken() && it.targetServer.isValidServerName()
            }
        parts.size == 2 && parts[0].equals("connect", ignoreCase = true) ->
            parts[1].trim().takeIf(String::isValidServerName)?.let { ProxyRequest.Connect(null, it) }
        parts.size == 1 && payload.isValidServerName() -> ProxyRequest.Connect(null, payload)
        else -> null
    }
}

internal fun parseTargetServer(data: ByteArray): String = parseProxyRequest(data)?.targetServer.orEmpty()

private fun String.isValidToken(): Boolean =
    isNotEmpty() && length <= 64 && none { it.isWhitespace() || it.isISOControl() || it == '|' }

private fun String.isValidServerName(): Boolean =
    isNotEmpty() && length <= MAX_SERVER_NAME_LENGTH && none { it.isWhitespace() || it.isISOControl() || it == '|' }
