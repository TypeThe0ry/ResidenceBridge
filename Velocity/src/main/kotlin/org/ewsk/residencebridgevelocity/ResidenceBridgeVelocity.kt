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
import com.velocitypowered.api.proxy.server.RegisteredServer
import org.slf4j.Logger
import jakarta.inject.Inject

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
        logger.info("ResidenceBridge-Velocity enabled.")
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        proxy.channelRegistrar.unregister(channel)
    }

    @Subscribe
    fun onPluginMessage(event: PluginMessageEvent) {
        if (event.identifier != channel) return
        event.result = PluginMessageEvent.ForwardResult.handled()

        val source = event.source as? ServerConnection ?: return
        val player = event.target as? Player ?: return
        if (source.player.uniqueId != player.uniqueId) {
            logger.warn("Rejected plugin message: source player does not match target ${player.username}.")
            return
        }

        when (val request = parseProxyRequest(event.data)) {
            is ProxyRequest.Status -> handleStatus(source, request)
            is ProxyRequest.Connect -> handleConnect(source, player, request)
            null -> return
        }
    }

    private fun handleStatus(source: ServerConnection, request: ProxyRequest.Status) {
        val server = findServer(request.targetServer)
        if (server == null) {
            respond(source, request.requestId, proxyStatus(serverRegistered = false))
            return
        }
        server.ping().whenComplete { _, error ->
            respond(source, request.requestId, proxyStatus(serverRegistered = true, pingSucceeded = error == null))
        }
    }

    private fun handleConnect(source: ServerConnection, player: Player, request: ProxyRequest.Connect) {
        val server = findServer(request.targetServer)
        if (server == null) {
            request.requestId?.let { respond(source, it, "connect-failed") }
            return
        }
        if (source.serverInfo.name.equals(server.serverInfo.name, ignoreCase = true)) {
            request.requestId?.let { respond(source, it, "connected") }
            return
        }
        player.createConnectionRequest(server).connect().whenComplete { result, error ->
            val connected = error == null && result?.isSuccessful == true
            request.requestId?.let { respond(source, it, if (connected) "connected" else "connect-failed") }
            if (!connected) {
                logger.warn("Failed to connect ${player.username} to ${request.targetServer}: ${error?.message ?: result?.status}")
            }
        }
    }

    private fun findServer(name: String): RegisteredServer? = proxy.getServer(name).orElseGet {
        proxy.allServers.firstOrNull { it.serverInfo.name.equals(name, ignoreCase = true) }
    }

    private fun respond(source: ServerConnection, requestId: String, status: String) {
        if (!source.sendPluginMessage(channel, "result|$requestId|$status".encodeToByteArray())) {
            logger.warn("Failed to return proxy result $status for request $requestId.")
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

internal fun proxyStatus(serverRegistered: Boolean, pingSucceeded: Boolean = false): String = when {
    !serverRegistered -> "not-found"
    pingSucceeded -> "available"
    else -> "offline"
}

internal fun parseProxyRequest(data: ByteArray): ProxyRequest? {
    if (data.isEmpty() || data.size > MAX_PAYLOAD_BYTES) return null
    val payload = data.decodeToString().trim()
    val parts = payload.split('|')
    return when {
        parts.size == 3 && parts[0].equals("status", ignoreCase = true) -> {
            val requestId = parts[1].trim()
            val server = parts[2].trim()
            if (requestId.isValidToken() && server.isValidServerName()) ProxyRequest.Status(requestId, server) else null
        }
        parts.size == 3 && parts[0].equals("connect", ignoreCase = true) -> {
            val requestId = parts[1].trim()
            val server = parts[2].trim()
            if (requestId.isValidToken() && server.isValidServerName()) ProxyRequest.Connect(requestId, server) else null
        }
        parts.size == 2 && parts[0].equals("connect", ignoreCase = true) -> {
            parts[1].trim().takeIf(String::isValidServerName)?.let { ProxyRequest.Connect(null, it) }
        }
        parts.size == 1 && payload.isValidServerName() -> ProxyRequest.Connect(null, payload)
        else -> null
    }
}

/** 兼容原有解析器 API 与旧测试。 */
internal fun parseTargetServer(data: ByteArray): String = parseProxyRequest(data)?.targetServer.orEmpty()

private fun String.isValidToken(): Boolean =
    isNotEmpty() && length <= 64 && none { it.isWhitespace() || it.isISOControl() || it == '|' }

private fun String.isValidServerName(): Boolean =
    isNotEmpty() && length <= MAX_SERVER_NAME_LENGTH && none { it.isWhitespace() || it.isISOControl() || it == '|' }
