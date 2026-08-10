package org.ewsk.residencebridge

import net.momirealms.sparrow.yaml.SparrowYaml
import net.momirealms.sparrow.yaml.YamlDocument
import org.bukkit.entity.Player
import java.io.File
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
        fun load(configFile: File): BridgeConfig {
            val yaml = SparrowYaml.builder().build()
            val doc = yaml.load(configFile.toPath())
            return BridgeConfig(
                serverId = doc.getOrDefault(String::class.java, "survival-1", "server-id").trim().lowercase(Locale.ROOT),
                mysql = MysqlConfig(
                    host = doc.getOrDefault(String::class.java, "127.0.0.1", "mysql", "host"),
                    port = doc.getOrDefault(Int::class.java, 3306, "mysql", "port"),
                    database = doc.getOrDefault(String::class.java, "minecraft", "mysql", "database"),
                    username = doc.getOrDefault(String::class.java, "root", "mysql", "username"),
                    password = doc.getOrDefault(String::class.java, "password", "mysql", "password"),
                    maximumPoolSize = doc.getOrDefault(Int::class.java, 10, "mysql", "maximum-pool-size")
                ),
                syncInitialDelayTicks = doc.getOrDefault(Long::class.java, 40L, "sync", "initial-delay-ticks"),
                syncIntervalSeconds = doc.getOrDefault(Long::class.java, 60L, "sync", "interval-seconds"),
                syncLogSuccess = doc.getOrDefault(Boolean::class.java, false, "sync", "log-success"),
                pendingExpireSeconds = doc.getOrDefault(Long::class.java, 30L, "teleport", "pending-expire-seconds"),
                joinDelayTicks = doc.getOrDefault(Long::class.java, 0L, "teleport", "join-delay-ticks"),
                teleportWait = TeleportWaitConfig(
                    enabled = doc.getOrDefault(Boolean::class.java, true, "teleport", "wait", "enabled"),
                    defaultSeconds = doc.getOrDefault(Int::class.java, 3, "teleport", "wait", "default-seconds"),
                    bypassPermission = doc.getOrDefault(String::class.java, "residencebridge.teleport.bypass", "teleport", "wait", "bypass-permission"),
                    cancelOnMove = doc.getOrDefault(Boolean::class.java, true, "teleport", "wait", "cancel-on-move"),
                    cancelOnDamage = doc.getOrDefault(Boolean::class.java, true, "teleport", "wait", "cancel-on-damage"),
                    countdownSound = doc.getOrDefault(String::class.java, "BLOCK_NOTE_BLOCK_PLING", "teleport", "wait", "countdown-sound"),
                    countdownSoundVolume = doc.getOrDefault(Double::class.java, 1.0, "teleport", "wait", "countdown-sound-volume").toFloat(),
                    countdownSoundPitch = doc.getOrDefault(Double::class.java, 1.2, "teleport", "wait", "countdown-sound-pitch").toFloat(),
                    rules = doc.permissionIntRules("teleport", "wait", "groups", valueKey = "seconds")
                ),
                limits = ResidenceLimitConfig(
                    defaultMaxResidences = doc.getOrDefault(Int::class.java, 3, "limits", "default-max-residences"),
                    bypassPermission = doc.getOrDefault(String::class.java, "residencebridge.limit.bypass", "limits", "bypass-permission"),
                    rules = doc.permissionIntRules("limits", "groups", valueKey = "max-residences")
                ),
                list = ListConfig(
                    pageSize = doc.getOrDefault(Int::class.java, 8, "list", "page-size").coerceAtLeast(1),
                    othersPermission = doc.getOrDefault(String::class.java, "residencebridge.list.others", "list", "others-permission"),
                    header = doc.message("list", "header", default = "&6你的全区领地列表 &7(&f%count%&7) &8- &7第 &f%page%&7/&f%max_page% &7页"),
                    otherHeader = doc.message("list", "other-header", default = "&6%target% 的全区领地列表 &7(&f%count%&7) &8- &7第 &f%page%&7/&f%max_page% &7页"),
                    line = doc.message("list", "line", default = "&7- &a%name% &8[&f%server%&8]"),
                    empty = doc.message("list", "empty", default = "&e你还没有任何领地。")
                ),
                remoteActionCommands = doc.getSequenceOrNull("remote-action-commands")
                    ?.value()
                    ?.mapNotNull { it.value()?.toString() }
                    ?.ifEmpty { listOf("rename", "give", "remove", "delete") }
                    ?.map { it.lowercase(Locale.ROOT) }
                    ?.toSet()
                    ?: setOf("rename", "give", "remove", "delete"),
                placeholderCacheSeconds = doc.getOrDefault(Long::class.java, 30L, "placeholder", "cache-seconds").coerceAtLeast(1L),
                velocityChannel = doc.getOrDefault(String::class.java, "residencebridge:main", "velocity", "channel"),
                fallbackBungeeChannel = doc.getOrDefault(Boolean::class.java, true, "velocity", "fallback-bungee-channel"),
                messages = Messages(
                    duplicate = doc.message("messages", "duplicate", default = "&c全服已存在同名领地：&f%name%"),
                    notFound = doc.message("messages", "not-found", default = "&c没有找到这个领地：&f%name%"),
                    switching = doc.message("messages", "switching", default = "&a正在传送到领地所在服务器：&f%server%"),
                    localTeleportFailed = doc.message("messages", "local-teleport-failed", default = "&c你没有权限执行这个操作。"),
                    connectRequestFailed = doc.message("messages", "connect-request-failed", default = "&c跨服传送请求失败，请稍后再试。"),
                    limitReached = doc.message("messages", "limit-reached", default = "&c你的全区领地数量已达上限：&f%count%/%max%"),
                    teleportWait = doc.message("messages", "teleport-wait", default = "&a传送将在 &f%seconds% &a秒后开始，请不要移动。"),
                    teleportCancelled = doc.message("messages", "teleport-cancelled", default = "&c传送已取消。"),
                    remoteActionSwitching = doc.message("messages", "remote-action-switching", default = "&a正在切换到领地所在服务器执行指令：&f%server%"),
                    remoteActionQueued = doc.message("messages", "remote-action-queued", default = "&a已到达目标服务器，正在执行指令。"),
                    noPermission = doc.message("messages", "no-permission", default = "&c你没有权限执行这个操作。")
                )
            )
        }

        private fun YamlDocument.message(vararg path: String, default: String): String {
            return MessageUtil.color(getOrDefault(String::class.java, default, *path))
        }

        private fun YamlDocument.permissionIntRules(vararg path: String, valueKey: String): List<PermissionIntRule> {
            val section = getSectionOrNull(*path) ?: return emptyList()
            return section.getValues().keys.mapNotNull { key ->
                val permission = getOrDefault(String::class.java, null, *path, key, "permission")
                    ?: return@mapNotNull null
                val value = getOrDefault(Int::class.java, 0, *path, key, valueKey)
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
    val enabled: Boolean,
    val defaultSeconds: Int,
    val bypassPermission: String,
    val cancelOnMove: Boolean,
    val cancelOnDamage: Boolean,
    val countdownSound: String,
    val countdownSoundVolume: Float,
    val countdownSoundPitch: Float,
    val rules: List<PermissionIntRule>
) {
    fun secondsFor(player: Player): Int {
        if (!enabled || player.isOp || player.hasPermission(bypassPermission) || player.hasPermission("residencebridge.admin")) {
            return 0
        }
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
    val othersPermission: String,
    val header: String,
    val otherHeader: String,
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
