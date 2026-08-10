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
        logger.info("ResidenceBridge-Velocity enabled.")
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        proxy.channelRegistrar.unregister(channel)
    }

    /**
     * 用 @Subscribe 直接订阅，不再在初始化时额外 eventManager.register()。
     * 原实现同时存在类级 @Subscribe 扫描与手动注册，代理重载时会重复注册，
     * 导致同一条消息被处理多次（表现为玩家被连续发起多次连接请求）。
     */
    @Subscribe
    fun onPluginMessage(event: PluginMessageEvent) {
        if (event.identifier != channel) {
            return
        }
        // 这是我们自己的私有通道，无论内容是否合法都不应转发给客户端。
        event.result = PluginMessageEvent.ForwardResult.handled()

        // 只接受后端服务器发来的消息。若来源是玩家连接，说明是客户端伪造的
        // 通道数据，直接丢弃——否则玩家可以自己发包把自己（或别人）传送到任意服务器。
        val source = event.source
        if (source !is ServerConnection) {
            return
        }
        val player = event.target as? Player ?: return

        // 校验消息主体就是发出该消息的那个玩家。Velocity 会把 target 设为消息所属的连接，
        // 这里再确认一次，避免后端被攻破后操纵其他在线玩家。
        if (source.player.uniqueId != player.uniqueId) {
            logger.warn("Rejected plugin message: source player does not match target ${player.username}.")
            return
        }

        val targetServer = parseTargetServer(event.data)

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
        // 已经在目标服务器上就不必再发连接请求。
        if (source.serverInfo.name.equals(server.serverInfo.name, ignoreCase = true)) {
            return
        }
        player.createConnectionRequest(server).connect().exceptionally {
            logger.warn("Failed to connect ${player.username} to $targetServer: ${it.message}")
            null
        }
    }

}

internal const val CONNECT_PREFIX = "connect|"
internal const val MAX_PAYLOAD_BYTES = 256
internal const val MAX_SERVER_NAME_LENGTH = 64

/**
 * 解析 `connect|<server>` 载荷，返回目标服务器名；无效载荷返回空串。
 *
 * 载荷来自后端服务器，属于不可信输入，所以先做长度上限检查再解码，
 * 避免异常大的字节数组被展开成字符串；解析出的服务器名要拿去查表，
 * 因此拒绝空白与控制字符。
 */
internal fun parseTargetServer(data: ByteArray): String {
    if (data.isEmpty() || data.size > MAX_PAYLOAD_BYTES) {
        return ""
    }
    val payload = data.decodeToString().trim()
    val raw = if (payload.startsWith(CONNECT_PREFIX, ignoreCase = true)) {
        payload.substring(CONNECT_PREFIX.length).trim()
    } else {
        payload
    }
    if (raw.isEmpty() || raw.length > MAX_SERVER_NAME_LENGTH) {
        return ""
    }
    if (raw.any { it.isWhitespace() || it.isISOControl() }) {
        return ""
    }
    return raw
}
