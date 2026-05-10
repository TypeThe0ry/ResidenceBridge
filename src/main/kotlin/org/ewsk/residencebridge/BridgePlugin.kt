package org.ewsk.residencebridge

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning
import taboolib.platform.BukkitPlugin
import java.util.Collections
import java.util.Locale
import java.util.UUID

object BridgePlugin {

    private lateinit var plugin: Plugin
    private lateinit var config: BridgeConfig
    private lateinit var database: BridgeDatabase
    private lateinit var messenger: VelocityMessenger
    private var syncTask: BukkitTask? = null
    private val bypassCreate = Collections.synchronizedSet(mutableSetOf<UUID>())
    private val bypassTeleport = Collections.synchronizedSet(mutableSetOf<UUID>())
    private val bypassRename = Collections.synchronizedSet(mutableSetOf<UUID>())

    fun enable() {
        plugin = BukkitPlugin.getInstance()
        plugin.saveDefaultConfig()
        start()
    }

    fun disable() {
        stop()
    }

    fun reload() {
        stop()
        plugin.reloadConfig()
        start()
    }

    private fun start() {
        config = BridgeConfig.load(plugin.config)
        database = BridgeDatabase(config)
        database.initTables()
        messenger = VelocityMessenger(plugin, config)
        messenger.register()
        scheduleSync()
    }

    private fun stop() {
        syncTask?.cancel()
        syncTask = null
        if (::messenger.isInitialized) {
            messenger.unregister()
        }
        if (::database.isInitialized) {
            database.close()
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val parsed = parseResidenceCommand(event.message) ?: return
        when (parsed.subCommand) {
            "create" -> handleCreate(event, parsed.args.getOrNull(0))
            "tp", "teleport" -> handleTeleport(event, parsed.args.getOrNull(0))
            "rename" -> handleRename(event, parsed.args.getOrNull(0), parsed.args.getOrNull(1))
            "remove", "delete" -> handleRemove(event, parsed.args.getOrNull(0))
        }
    }

    @SubscribeEvent
    fun onJoin(event: PlayerJoinEvent) {
        val uuid = event.player.uniqueId
        runAsync {
            val pending = database.consumePending(uuid) ?: return@runAsync
            runSync(config.joinDelayTicks) {
                val player = Bukkit.getPlayer(uuid) ?: return@runSync
                localTeleport(player, pending.residenceName)
            }
        }
    }

    private fun handleCreate(event: PlayerCommandPreprocessEvent, name: String?) {
        val player = event.player
        val residenceName = name ?: return
        if (bypassCreate.remove(player.uniqueId)) {
            return
        }
        event.isCancelled = true
        runAsync {
            val reserved = database.reserveName(residenceName)
            if (!reserved) {
                player.sendBridgeMessage(config.messages.duplicate.replace("%name%", residenceName))
                return@runAsync
            }
            runSync {
                bypassCreate.add(player.uniqueId)
                player.performCommand("res create $residenceName")
                runSync(40L) { confirmCreated(residenceName) }
            }
        }
    }

    private fun confirmCreated(name: String) {
        val snapshot = ResidenceHook.toSnapshot(name)
        runAsync {
            if (snapshot != null) {
                database.upsertSnapshot(snapshot)
            } else {
                database.deleteReservationIfLocal(name)
            }
        }
    }

    private fun handleTeleport(event: PlayerCommandPreprocessEvent, name: String?) {
        val player = event.player
        val residenceName = name ?: return
        if (bypassTeleport.remove(player.uniqueId)) {
            return
        }
        event.isCancelled = true
        runAsync {
            val entry = database.findIndex(residenceName)
            if (entry == null) {
                player.sendBridgeMessage(config.messages.notFound.replace("%name%", residenceName))
                return@runAsync
            }
            if (entry.serverId.equals(config.serverId, ignoreCase = true)) {
                runSync { localTeleport(player, entry.displayName) }
                return@runAsync
            }
            val expireAt = System.currentTimeMillis() + config.pendingExpireSeconds * 1000L
            database.writePending(player.uniqueId, player.name, entry.displayName, entry.serverId, expireAt)
            runSync {
                val ok = messenger.requestConnect(player, entry.serverId)
                if (ok) {
                    player.sendBridgeMessage(config.messages.switching.replace("%server%", entry.serverId))
                } else {
                    player.sendBridgeMessage(config.messages.connectRequestFailed)
                }
            }
        }
    }

    private fun handleRename(event: PlayerCommandPreprocessEvent, oldName: String?, newName: String?) {
        val player = event.player
        if (oldName == null || newName == null) {
            return
        }
        if (bypassRename.remove(player.uniqueId)) {
            return
        }
        event.isCancelled = true

        val oldExists = ResidenceHook.exists(oldName)
        if (!oldExists) {
            player.sendMessage(config.messages.notFound.replace("%name%", oldName))
            return
        }

        val sameNameKey = key(oldName) == key(newName)
        runAsync {
            if (!sameNameKey) {
                val reserved = database.reserveName(newName)
                if (!reserved) {
                    player.sendBridgeMessage(config.messages.duplicate.replace("%name%", newName))
                    return@runAsync
                }
            }
            runSync {
                bypassRename.add(player.uniqueId)
                player.performCommand("res rename $oldName $newName")
                runSync(40L) { confirmRenamed(oldName, newName, sameNameKey) }
            }
        }
    }

    private fun confirmRenamed(oldName: String, newName: String, sameNameKey: Boolean) {
        val newSnapshot = ResidenceHook.toSnapshot(newName)
        val oldStillExists = if (sameNameKey) false else ResidenceHook.exists(oldName)
        runAsync {
            if (newSnapshot != null && !oldStillExists) {
                database.replaceRenamed(oldName, newSnapshot)
            } else if (!sameNameKey) {
                database.deleteReservationIfLocal(newName)
            }
        }
    }

    private fun handleRemove(event: PlayerCommandPreprocessEvent, name: String?) {
        val residenceName = name ?: return
        val existedBefore = ResidenceHook.exists(residenceName)
        if (!existedBefore) {
            return
        }
        runSync(40L) {
            if (!ResidenceHook.exists(residenceName)) {
                runAsync { database.delete(residenceName) }
            }
        }
    }

    private fun localTeleport(player: Player, residenceName: String) {
        val ok = ResidenceHook.teleport(player, residenceName)
        if (!ok) {
            bypassTeleport.add(player.uniqueId)
            val performed = player.performCommand("res tp $residenceName")
            if (!performed) {
                player.sendBridgeMessage(config.messages.localTeleportFailed)
            }
        }
    }

    private fun scheduleSync() {
        syncTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            val snapshots = ResidenceHook.allSnapshots()
            runAsync {
                try {
                    database.syncServerSnapshots(snapshots)
                    if (config.syncLogSuccess) {
                        info("Synced ${snapshots.size} residences for ${config.serverId}.")
                    }
                } catch (t: Throwable) {
                    warning("Residence sync failed: ${t.message}")
                }
            }
        }, config.syncInitialDelayTicks, config.syncIntervalSeconds * 20L)
    }

    private fun runAsync(block: () -> Unit) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                block()
            } catch (t: Throwable) {
                warning("Async task failed: ${t.message}")
                t.printStackTrace()
            }
        })
    }

    private fun runSync(delay: Long = 0L, block: () -> Unit) {
        if (delay <= 0L) {
            Bukkit.getScheduler().runTask(plugin, Runnable(block))
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable(block), delay)
        }
    }

    private fun Player.sendBridgeMessage(message: String) {
        Bukkit.getScheduler().runTask(plugin, Runnable { sendMessage(message) })
    }

    private data class ParsedResidenceCommand(val subCommand: String, val args: List<String>)

    private fun parseResidenceCommand(message: String): ParsedResidenceCommand? {
        val parts = message.removePrefix("/").trim().split(Regex("\\s+"))
        if (parts.size < 2) {
            return null
        }
        val root = parts[0].lowercase(Locale.ROOT)
        if (root != "res" && root != "residence") {
            return null
        }
        return ParsedResidenceCommand(parts[1].lowercase(Locale.ROOT), parts.drop(2))
    }
}
