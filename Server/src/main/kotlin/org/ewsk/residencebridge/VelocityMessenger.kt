package org.ewsk.residencebridge

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.messaging.PluginMessageListener
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal enum class ServerAvailability { AVAILABLE, NOT_FOUND, OFFLINE, UNAVAILABLE }
internal data class ProxyResult(val requestId: String, val status: String)
private data class PendingRequest(val playerUuid: UUID, val targetServer: String, val callback: (String) -> Unit)

internal class VelocityMessenger(private val plugin: Plugin, private val config: BridgeConfig) : PluginMessageListener {
    private val sequence = AtomicLong()
    private val pending = ConcurrentHashMap<String, PendingRequest>()

    fun register() {
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, config.velocityChannel)
        plugin.server.messenger.registerIncomingPluginChannel(plugin, config.velocityChannel, this)
        if (config.fallbackBungeeChannel) plugin.server.messenger.registerOutgoingPluginChannel(plugin, "BungeeCord")
    }

    fun unregister() {
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, config.velocityChannel)
        plugin.server.messenger.unregisterIncomingPluginChannel(plugin, config.velocityChannel, this)
        pending.clear()
        if (config.fallbackBungeeChannel) plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, "BungeeCord")
    }

    fun checkAvailability(player: Player, targetServer: String, callback: (ServerAvailability) -> Unit) {
        if (!targetServer.isValidProtocolToken()) {
            callback(ServerAvailability.UNAVAILABLE)
            return
        }
        val requestId = nextRequestId()
        pending[requestId] = PendingRequest(player.uniqueId, targetServer) { status ->
            callback(when (status) {
                "available" -> ServerAvailability.AVAILABLE
                "not-found" -> ServerAvailability.NOT_FOUND
                "offline" -> ServerAvailability.OFFLINE
                else -> ServerAvailability.UNAVAILABLE
            })
        }
        if (!sendPrivate(player, "status|$requestId|$targetServer")) {
            pending.remove(requestId)?.callback?.invoke("unavailable")
            return
        }
        BridgeScheduler.runGlobal(config.serverStatusTimeoutTicks) {
            pending.remove(requestId)?.callback?.invoke("unavailable")
        }
    }

    fun requestConnect(player: Player, targetServer: String, onFailure: () -> Unit): Boolean {
        if (!targetServer.isValidProtocolToken()) return false
        val requestId = nextRequestId()
        pending[requestId] = PendingRequest(player.uniqueId, targetServer) { status ->
            if (status == "connect-failed") onFailure()
        }
        if (!sendPrivate(player, "connect|$requestId|$targetServer")) {
            pending.remove(requestId)
            return false
        }
        BridgeScheduler.runGlobal(config.serverStatusTimeoutTicks) { pending.remove(requestId) }
        return true
    }

    /** Exactly one compatibility fallback after a status request cannot be answered. */
    fun requestFallbackConnect(player: Player, targetServer: String): Boolean {
        if (!targetServer.isValidProtocolToken()) return false
        return if (config.fallbackBungeeChannel) {
            runCatching { sendBungeeConnect(player, targetServer); true }.getOrElse {
                plugin.logger.warning("Failed to send BungeeCord connect fallback: ${it.message}")
                false
            }
        } else {
            sendPrivate(player, "connect|$targetServer")
        }
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (!channel.equals(config.velocityChannel, ignoreCase = true)) return
        val result = parseProxyResult(message) ?: return
        val request = pending[result.requestId] ?: return
        if (request.playerUuid != player.uniqueId) return
        if (result.status in setOf("available", "not-found", "offline") && !player.isOnline) return
        pending.remove(result.requestId, request)
        request.callback(result.status)
    }

    private fun sendPrivate(player: Player, payload: String): Boolean = try {
        player.sendPluginMessage(plugin, config.velocityChannel, payload.encodeToByteArray())
        true
    } catch (t: Throwable) {
        plugin.logger.warning("Failed to send proxy request on ${config.velocityChannel}: ${t.message}")
        false
    }

    private fun nextRequestId(): String = sequence.incrementAndGet().toString(36) + UUID.randomUUID().toString().take(8)

    private fun sendBungeeConnect(player: Player, targetServer: String) {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data -> data.writeUTF("Connect"); data.writeUTF(targetServer) }
        player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray())
    }
}

internal fun parseProxyResult(data: ByteArray): ProxyResult? {
    if (data.isEmpty() || data.size > 256) return null
    val parts = data.decodeToString().trim().split('|')
    if (parts.size != 3 || !parts[0].equals("result", ignoreCase = true)) return null
    val requestId = parts[1]
    val status = parts[2].lowercase()
    if (!requestId.isValidProtocolToken() || status !in PROXY_STATUSES) return null
    return ProxyResult(requestId, status)
}

internal fun String.isValidProtocolToken(maxLength: Int = 64): Boolean =
    isNotEmpty() && length <= maxLength && none { it.isWhitespace() || it.isISOControl() || it == '|' }

private val PROXY_STATUSES = setOf("available", "not-found", "offline", "connected", "connect-failed")
