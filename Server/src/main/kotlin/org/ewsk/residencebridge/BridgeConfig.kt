package org.ewsk.residencebridge

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import java.util.Locale

data class BridgeConfig(
    val serverId: String,
    val mysql: MysqlConfig,
    val syncInitialDelayTicks: Long,
    val syncIntervalSeconds: Long,
    val syncLogSuccess: Boolean,
    val pendingExpireSeconds: Long,
    val joinDelayTicks: Long,
    val teleportWait: TeleportWaitConfig,
    val limits: ResidenceLimitConfig,
    val list: ListConfig,
    val remoteActionCommands: Set<String>,
    val placeholderCacheSeconds: Long,
    val velocityChannel: String,
    val fallbackBungeeChannel: Boolean,
    val messages: Messages
) {
    companion object {
        fun load(config: FileConfiguration): BridgeConfig {
            return BridgeConfig(
                serverId = config.getString("server-id", "survival-1")!!.trim().lowercase(Locale.ROOT),
                mysql = MysqlConfig(
                    host = config.getString("mysql.host", "127.0.0.1")!!,
                    port = config.getInt("mysql.port", 3306),
                    database = config.getString("mysql.database", "minecraft")!!,
                    username = config.getString("mysql.username", "root")!!,
                    password = config.getString("mysql.password", "password")!!,
                    maximumPoolSize = config.getInt("mysql.maximum-pool-size", 10)
                ),
                syncInitialDelayTicks = config.getLong("sync.initial-delay-ticks", 40L),
                syncIntervalSeconds = config.getLong("sync.interval-seconds", 60L),
                syncLogSuccess = config.getBoolean("sync.log-success", false),
                pendingExpireSeconds = config.getLong("teleport.pending-expire-seconds", 30L),
                joinDelayTicks = config.getLong("teleport.join-delay-ticks", 40L),
                teleportWait = TeleportWaitConfig(
                    defaultSeconds = config.getInt("teleport.wait.default-seconds", 3),
                    cancelOnMove = config.getBoolean("teleport.wait.cancel-on-move", true),
                    cancelOnDamage = config.getBoolean("teleport.wait.cancel-on-damage", true),
                    rules = config.permissionIntRules("teleport.wait.groups", "seconds")
                ),
                limits = ResidenceLimitConfig(
                    defaultMaxResidences = config.getInt("limits.default-max-residences", 3),
                    bypassPermission = config.getString("limits.bypass-permission", "residencebridge.limit.bypass")!!,
                    rules = config.permissionIntRules("limits.groups", "max-residences")
                ),
                list = ListConfig(
                    pageSize = config.getInt("list.page-size", 8).coerceAtLeast(1),
                    header = config.message("list.header", "&6你的全区领地列表 &7(&f%count%&7) &8- &7第 &f%page%&7/&f%max_page% &7页"),
                    line = config.message("list.line", "&7- &a%name% &8[&f%server%&8]"),
                    empty = config.message("list.empty", "&e你还没有任何领地。")
                ),
                remoteActionCommands = config.getStringList("remote-action-commands")
                    .ifEmpty { listOf("rename", "give", "remove", "delete") }
                    .map { it.lowercase(Locale.ROOT) }
                    .toSet(),
                placeholderCacheSeconds = config.getLong("placeholder.cache-seconds", 30L).coerceAtLeast(1L),
                velocityChannel = config.getString("velocity.channel", "residencebridge:main")!!,
                fallbackBungeeChannel = config.getBoolean("velocity.fallback-bungee-channel", true),
                messages = Messages(
                    duplicate = config.message("messages.duplicate", "&c全服已存在同名领地：&f%name%"),
                    notFound = config.message("messages.not-found", "&c没有找到这个领地：&f%name%"),
                    switching = config.message("messages.switching", "&a正在传送到领地所在服务器：&f%server%"),
                    localTeleportFailed = config.message("messages.local-teleport-failed", "&c本服领地传送失败，请联系管理员。"),
                    connectRequestFailed = config.message("messages.connect-request-failed", "&c跨服传送请求失败，请稍后再试。"),
                    limitReached = config.message("messages.limit-reached", "&c你的全区领地数量已达上限：&f%count%/%max%"),
                    teleportWait = config.message("messages.teleport-wait", "&a传送将在 &f%seconds% &a秒后开始，请不要移动。"),
                    teleportCancelled = config.message("messages.teleport-cancelled", "&c传送已取消。"),
                    remoteActionSwitching = config.message("messages.remote-action-switching", "&a正在切换到领地所在服务器执行指令：&f%server%"),
                    remoteActionQueued = config.message("messages.remote-action-queued", "&a已到达目标服务器，正在执行指令。"),
                    noPermission = config.message("messages.no-permission", "&c你没有权限执行这个操作。")
                )
            )
        }

        private fun FileConfiguration.message(path: String, default: String): String {
            return MessageUtil.color(getString(path, default)!!)
        }

        private fun FileConfiguration.permissionIntRules(path: String, valueKey: String): List<PermissionIntRule> {
            val section = getConfigurationSection(path) ?: return emptyList()
            return section.getKeys(false).mapNotNull { key ->
                val permission = section.getString("$key.permission") ?: return@mapNotNull null
                val value = section.getInt("$key.$valueKey")
                PermissionIntRule(permission, value)
            }
        }
    }
}

data class MysqlConfig(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val maximumPoolSize: Int
)

data class PermissionIntRule(
    val permission: String,
    val value: Int
)

data class TeleportWaitConfig(
    val defaultSeconds: Int,
    val cancelOnMove: Boolean,
    val cancelOnDamage: Boolean,
    val rules: List<PermissionIntRule>
) {
    fun secondsFor(player: Player): Int {
        val values = rules.filter { player.hasPermission(it.permission) }.map { it.value }
        return (values.minOrNull() ?: defaultSeconds).coerceAtLeast(0)
    }
}

data class ResidenceLimitConfig(
    val defaultMaxResidences: Int,
    val bypassPermission: String,
    val rules: List<PermissionIntRule>
) {
    fun maxFor(player: Player): Int {
        if (player.hasPermission(bypassPermission)) {
            return Int.MAX_VALUE
        }
        val values = rules.filter { player.hasPermission(it.permission) }.map { it.value }
        return (values.maxOrNull() ?: defaultMaxResidences).coerceAtLeast(0)
    }
}

data class ListConfig(
    val pageSize: Int,
    val header: String,
    val line: String,
    val empty: String
)

data class Messages(
    val duplicate: String,
    val notFound: String,
    val switching: String,
    val localTeleportFailed: String,
    val connectRequestFailed: String,
    val limitReached: String,
    val teleportWait: String,
    val teleportCancelled: String,
    val remoteActionSwitching: String,
    val remoteActionQueued: String,
    val noPermission: String
)
