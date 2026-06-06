package org.ewsk.residencebridge

import org.bukkit.ChatColor
import org.bukkit.configuration.file.FileConfiguration
import java.util.Locale

data class BridgeConfig(
    val serverId: String,
    val mysql: MysqlConfig,
    val syncInitialDelayTicks: Long,
    val syncIntervalSeconds: Long,
    val syncLogSuccess: Boolean,
    val pendingExpireSeconds: Long,
    val joinDelayTicks: Long,
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
                velocityChannel = config.getString("velocity.channel", "residencebridge:main")!!,
                fallbackBungeeChannel = config.getBoolean("velocity.fallback-bungee-channel", true),
                messages = Messages(
                    duplicate = config.message("messages.duplicate", "&c全服已存在同名领地：&f%name%"),
                    notFound = config.message("messages.not-found", "&c没有找到这个领地：&f%name%"),
                    switching = config.message("messages.switching", "&a正在传送到领地所在服务器：&f%server%"),
                    localTeleportFailed = config.message("messages.local-teleport-failed", "&c本服领地传送失败，请联系管理员。"),
                    connectRequestFailed = config.message("messages.connect-request-failed", "&c跨服传送请求失败，请稍后再试。")
                )
            )
        }

        private fun FileConfiguration.message(path: String, default: String): String {
            return ChatColor.translateAlternateColorCodes('&', getString(path, default)!!)
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

data class Messages(
    val duplicate: String,
    val notFound: String,
    val switching: String,
    val localTeleportFailed: String,
    val connectRequestFailed: String
)
