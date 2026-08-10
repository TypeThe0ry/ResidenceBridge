package org.ewsk.residencebridge

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.messaging.PluginMessageListener
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal enum class ServerAvailability {
    AVAILABLE,
    NOT_FOUND,
    OFFLINE,
    UNAVAILABLE
}

internal data class ProxyResult(val requestId: String, val status: String)

internal class VelocityMessenger(private val plugin: Plugin, private val config: BridgeConfig) : PluginMessageListener {
    private val requestSequence = AtomicLong()
    private val pendingChecks = ConcurrentHashMap<String, (ServerAvailability) -> Unit>()
    private val pendingConnections = ConcurrentHashMap<String, (Boolean) -> Unit>()

    fun register() {
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, config.velocityChannel)
        plugin.server.messenger.registerIncomingPluginChannel(plugin, config.velocityChannel, this)
        if (config.fallbackBungeeChannel) {
            plugin.server.messenger.registerOutgoingPluginChannel(plugin, "BungeeCord")
        }
    }

    fun unregister() {
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, config.velocityChannel)
        plugin.server.messenger.unregisterIncomingPluginChannel(plugin, config.velocityChannel, this)
        pendingChecks.clear()
        pendingConnections.clear()
        if (config.fallbackBungeeChannel) {
            plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, "BungeeCord")
        }
    }

    fun checkAvailability(player: Player, targetServer: String, callback: (ServerAvailability) -> Unit) {
        val requestId = nextRequestId()
        pendingChecks[requestId] = callback
        val sent = sendPrivate(player, "status|$requestId|$targetServer")
        if (!sent) {
            pendingChecks.remove(requestId)?.invoke(ServerAvailability.UNAVAILABLE)
            return
        }
        BridgeScheduler.runGlobal(config.serverStatusTimeoutTicks) {
            pendingChecks.remove(requestId)?.invoke(ServerAvailability.UNAVAILABLE)
        }
    }

    /**
     * 仅在可用性检查成功后调用。返回值只表示请求是否成功发给代理；
     * 代理若随后连接失败，会通过 [onFailure] 回传。成功切服时旧后端连接会关闭，
     * 因此不能依赖成功回包来显示“正在切换”。
     */
    fun requestConnect(player: Player, targetServer: String, onFailure: () -> Unit): Boolean {
        val requestId = nextRequestId()
        pendingConnections[requestId] = { connected -> if (!connected) onFailure() }
        if (sendPrivate(player, "connect|$requestId|$targetServer")) {
            BridgeScheduler.runGlobal(config.serverStatusTimeoutTicks) {
                pendingConnections.remove(requestId)
            }
            return true
        }
        pendingConnections.remove(requestId)
        if (!config.fallbackBungeeChannel) return false
        return runCatching { sendBungeeConnect(player, targetServer); true }.getOrDefault(false)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (!channel.equals(config.velocityChannel, ignoreCase = true)) return
        val result = parseProxyResult(message) ?: return
        when (result.status) {
            "available" -> pendingChecks.remove(result.requestId)?.invoke(ServerAvailability.AVAILABLE)
            "not-found" -> pendingChecks.remove(result.requestId)?.invoke(ServerAvailability.NOT_FOUND)
            "offline" -> pendingChecks.remove(result.requestId)?.invoke(ServerAvailability.OFFLINE)
            "connected" -> pendingConnections.remove(result.requestId)?.invoke(true)
            "connect-failed" -> pendingConnections.remove(result.requestId)?.invoke(false)
        }
    }

    private fun sendPrivate(player: Player, payload: String): Boolean = try {
        player.sendPluginMessage(plugin, config.velocityChannel, payload.encodeToByteArray())
        true
    } catch (t: Throwable) {
        plugin.logger.warning("Failed to send proxy request on ${config.velocityChannel}: ${t.message}")
        false
    }

    private fun nextRequestId(): String = requestSequence.incrementAndGet().toString(36) + UUID.randomUUID().toString().take(8)

    private fun sendBungeeConnect(player: Player, targetServer: String) {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeUTF("Connect")
            data.writeUTF(targetServer)
        }
        player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray())
    }
}

internal fun parseProxyResult(data: ByteArray): ProxyResult? {
    if (data.isEmpty() || data.size > 256) return null
    val parts = data.decodeToString().trim().split('|')
    if (parts.size != 3 || !parts[0].equals("result", ignoreCase = true)) return null
    val requestId = parts[1]
    val status = parts[2].lowercase()
    if (!requestId.isValidProtocolToken() || status !in PROXY_RESULT_STATUSES) return null
    return ProxyResult(requestId, status)
}

internal fun String.isValidProtocolToken(maxLength: Int = 64): Boolean =
    isNotEmpty() && length <= maxLength && none { it.isWhitespace() || it.isISOControl() || it == '|' }

private val PROXY_RESULT_STATUSES = setOf("available", "not-found", "offline", "connected", "connect-failed")
