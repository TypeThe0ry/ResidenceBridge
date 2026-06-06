package org.ewsk.residencebridge

import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning
import taboolib.platform.BukkitPlugin
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object BridgePlugin {

    private lateinit var plugin: Plugin
    private lateinit var config: BridgeConfig
    private lateinit var database: BridgeDatabase
    private lateinit var messenger: VelocityMessenger
    private var syncTask: BridgeTask? = null
    private val bypassCreate = Collections.synchronizedSet(mutableSetOf<UUID>())
    private val bypassRename = Collections.synchronizedSet(mutableSetOf<UUID>())
    private val bypassCommand = Collections.synchronizedSet(mutableSetOf<UUID>())
    private val waitingTeleports = ConcurrentHashMap<UUID, WaitingTeleport>()

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
        BridgeScheduler.init(plugin)
        config = BridgeConfig.load(plugin.config)
        database = BridgeDatabase(config)
        database.initTables()
        messenger = VelocityMessenger(plugin, config)
        messenger.register()
        PlaceholderBridge.register(config, database)
        scheduleSync()
    }

    private fun stop() {
        syncTask?.cancel()
        syncTask = null
        waitingTeleports.values.forEach { it.task?.cancel() }
        waitingTeleports.clear()
        PlaceholderBridge.unregister()
        if (::messenger.isInitialized) {
            messenger.unregister()
        }
        if (::database.isInitialized) {
            database.close()
        }
        BridgeScheduler.shutdown()
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (bypassCommand.remove(event.player.uniqueId)) {
            return
        }
        val parsed = parseResidenceCommand(event.message) ?: return
        when (parsed.subCommand) {
            "list" -> handleList(event, parsed)
            "create" -> handleCreate(event, parsed.args.getOrNull(0))
            "tp", "teleport" -> handleTeleport(event, parsed.args.getOrNull(0))
            "rename" -> handleRename(event, parsed)
            "remove", "delete" -> handleRemove(event, parsed)
            else -> if (config.remoteActionCommands.contains(parsed.subCommand)) {
                handleRemoteAction(event, parsed, parsed.args.getOrNull(0))
            }
        }
    }

    @SubscribeEvent
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val uuid = player.uniqueId
        runAsync {
            val pendingTeleport = database.consumePending(uuid)
            val pendingActions = database.consumePendingActions(uuid)
            if (pendingTeleport == null && pendingActions.isEmpty()) {
                return@runAsync
            }
            runPlayer(player, config.joinDelayTicks) {
                pendingActions.forEach { executePendingAction(player, it) }
                if (pendingTeleport != null) {
                    localTeleport(player, pendingTeleport.residenceName)
                }
            }
        }
    }

    @SubscribeEvent
    fun onMove(event: PlayerMoveEvent) {
        if (!config.teleportWait.cancelOnMove) {
            return
        }
        val waiting = waitingTeleports[event.player.uniqueId] ?: return
        val to = event.to ?: return
        if (waiting.worldName != to.world?.name || waiting.x != to.blockX || waiting.y != to.blockY || waiting.z != to.blockZ) {
            cancelWaitingTeleport(event.player)
        }
    }

    @SubscribeEvent
    fun onDamage(event: EntityDamageEvent) {
        if (!config.teleportWait.cancelOnDamage) {
            return
        }
        val player = event.entity as? Player ?: return
        if (waitingTeleports.containsKey(player.uniqueId)) {
            cancelWaitingTeleport(player)
        }
    }

    @SubscribeEvent
    fun onQuit(event: PlayerQuitEvent) {
        waitingTeleports.remove(event.player.uniqueId)?.task?.cancel()
    }

    private fun handleList(event: PlayerCommandPreprocessEvent, parsed: ParsedResidenceCommand) {
        val player = event.player
        val page = parsed.args.firstOrNull()?.toIntOrNull() ?: 1
        event.isCancelled = true
        runAsync {
            val result = database.listResidencesByOwner(player.uniqueId, player.name, page, config.list.pageSize)
            runPlayer(player) {
                if (result.total <= 0) {
                    player.sendMessage(config.list.empty)
                    return@runPlayer
                }
                player.sendMessage(
                    MessageUtil.apply(
                        config.list.header,
                        mapOf("count" to result.total, "page" to result.page, "max_page" to result.maxPage)
                    )
                )
                result.entries.forEachIndexed { index, entry ->
                    player.sendMessage(
                        MessageUtil.apply(
                            config.list.line,
                            mapOf(
                                "index" to ((result.page - 1) * result.pageSize + index + 1),
                                "name" to entry.displayName,
                                "server" to entry.serverId,
                                "world" to entry.worldName,
                                "owner" to entry.ownerName
                            )
                        )
                    )
                }
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
        val maxResidences = config.limits.maxFor(player)
        runAsync {
            val reserved = database.tryReserveCreate(residenceName, player.uniqueId, player.name, maxResidences)
            when (reserved.status) {
                CreateReservationStatus.DUPLICATE -> player.sendBridgeMessage(
                    MessageUtil.apply(config.messages.duplicate, mapOf("name" to residenceName))
                )
                CreateReservationStatus.LIMIT_REACHED -> player.sendBridgeMessage(
                    MessageUtil.apply(
                        config.messages.limitReached,
                        mapOf("count" to reserved.count, "max" to formatMax(reserved.max))
                    )
                )
                CreateReservationStatus.RESERVED -> runPlayer(player) {
                    bypassCreate.add(player.uniqueId)
                    player.performCommand("res create $residenceName")
                    runPlayer(player, 40L) { confirmCreated(residenceName) }
                }
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
        event.isCancelled = true
        runAsync {
            val entry = database.findIndex(residenceName)
            if (entry == null) {
                player.sendBridgeMessage(MessageUtil.apply(config.messages.notFound, mapOf("name" to residenceName)))
                return@runAsync
            }
            runPlayer(player) { startTeleport(player, entry) }
        }
    }

    private fun startTeleport(player: Player, entry: ResidenceIndexEntry) {
        val seconds = config.teleportWait.secondsFor(player)
        if (seconds <= 0) {
            executeTeleport(player, entry)
            return
        }
        val location = player.location
        val waiting = WaitingTeleport(entry, location.world?.name, location.blockX, location.blockY, location.blockZ)
        waitingTeleports.remove(player.uniqueId)?.task?.cancel()
        waitingTeleports[player.uniqueId] = waiting
        player.sendMessage(MessageUtil.apply(config.messages.teleportWait, mapOf("seconds" to seconds, "name" to entry.displayName)))
        waiting.task = runPlayer(player, seconds * 20L) {
            val active = waitingTeleports.remove(player.uniqueId) ?: return@runPlayer
            if (active === waiting) {
                executeTeleport(player, entry)
            }
        }
    }

    private fun cancelWaitingTeleport(player: Player) {
        waitingTeleports.remove(player.uniqueId)?.task?.cancel() ?: return
        player.sendMessage(config.messages.teleportCancelled)
    }

    private fun executeTeleport(player: Player, entry: ResidenceIndexEntry) {
        if (entry.serverId.equals(config.serverId, ignoreCase = true)) {
            localTeleport(player, entry.displayName)
            return
        }
        runAsync {
            val expireAt = System.currentTimeMillis() + config.pendingExpireSeconds * 1000L
            database.writePending(player.uniqueId, player.name, entry.displayName, entry.serverId, expireAt)
            runPlayer(player) { connectToServer(player, entry.serverId, config.messages.switching) }
        }
    }

    private fun handleRename(event: PlayerCommandPreprocessEvent, parsed: ParsedResidenceCommand) {
        val player = event.player
        val oldName = parsed.args.getOrNull(0) ?: return
        val newName = parsed.args.getOrNull(1) ?: return
        if (bypassRename.remove(player.uniqueId)) {
            return
        }
        event.isCancelled = true

        if (ResidenceHook.exists(oldName)) {
            val sameNameKey = key(oldName) == key(newName)
            runAsync {
                if (!sameNameKey) {
                    val reserved = database.reserveName(newName, config.serverId, player.uniqueId, player.name)
                    if (!reserved) {
                        player.sendBridgeMessage(MessageUtil.apply(config.messages.duplicate, mapOf("name" to newName)))
                        return@runAsync
                    }
                }
                runPlayer(player) {
                    bypassRename.add(player.uniqueId)
                    player.performCommand(parsed.rawCommand)
                    runPlayer(player, 40L) { confirmRenamed(oldName, newName, sameNameKey) }
                }
            }
            return
        }

        runAsync {
            val entry = database.findIndex(oldName)
            if (entry == null) {
                player.sendBridgeMessage(MessageUtil.apply(config.messages.notFound, mapOf("name" to oldName)))
                return@runAsync
            }
            if (entry.serverId.equals(config.serverId, ignoreCase = true)) {
                player.sendBridgeMessage(MessageUtil.apply(config.messages.notFound, mapOf("name" to oldName)))
                return@runAsync
            }
            if (key(oldName) != key(newName)) {
                val reserved = database.reserveName(newName, entry.serverId, entry.ownerUuid, entry.ownerName)
                if (!reserved) {
                    player.sendBridgeMessage(MessageUtil.apply(config.messages.duplicate, mapOf("name" to newName)))
                    return@runAsync
                }
            }
            queueRemoteAction(player, parsed, entry)
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

    private fun handleRemove(event: PlayerCommandPreprocessEvent, parsed: ParsedResidenceCommand) {
        val residenceName = parsed.args.getOrNull(0) ?: return
        if (ResidenceHook.exists(residenceName)) {
            runPlayer(event.player, 40L) { confirmRemoved(residenceName) }
            return
        }
        handleRemoteAction(event, parsed, residenceName)
    }

    private fun handleRemoteAction(event: PlayerCommandPreprocessEvent, parsed: ParsedResidenceCommand, residenceName: String?) {
        val player = event.player
        val targetResidence = residenceName ?: return
        if (ResidenceHook.exists(targetResidence)) {
            runPlayer(player, 40L) { confirmActionSnapshot(parsed) }
            return
        }
        event.isCancelled = true
        runAsync {
            val entry = database.findIndex(targetResidence)
            if (entry == null) {
                player.sendBridgeMessage(MessageUtil.apply(config.messages.notFound, mapOf("name" to targetResidence)))
                return@runAsync
            }
            if (entry.serverId.equals(config.serverId, ignoreCase = true)) {
                runPlayer(player) {
                    bypassCommand.add(player.uniqueId)
                    player.performCommand(parsed.rawCommand)
                    runPlayer(player, 40L) { confirmActionSnapshot(parsed) }
                }
                return@runAsync
            }
            queueRemoteAction(player, parsed, entry)
        }
    }

    private fun queueRemoteAction(player: Player, parsed: ParsedResidenceCommand, entry: ResidenceIndexEntry) {
        val expireAt = System.currentTimeMillis() + config.pendingExpireSeconds * 1000L
        database.writePendingAction(player.uniqueId, player.name, parsed.subCommand, parsed.rawCommand, entry.displayName, entry.serverId, expireAt)
        runPlayer(player) { connectToServer(player, entry.serverId, config.messages.remoteActionSwitching) }
    }

    private fun executePendingAction(player: Player, action: PendingAction) {
        player.sendMessage(config.messages.remoteActionQueued)
        bypassCommand.add(player.uniqueId)
        player.performCommand(action.commandText.removePrefix("/"))
        val parsed = parseResidenceCommand(action.commandText) ?: return
        runPlayer(player, 40L) { confirmActionSnapshot(parsed) }
    }

    private fun confirmActionSnapshot(parsed: ParsedResidenceCommand) {
        when (parsed.subCommand) {
            "rename" -> {
                val oldName = parsed.args.getOrNull(0) ?: return
                val newName = parsed.args.getOrNull(1) ?: return
                confirmRenamed(oldName, newName, key(oldName) == key(newName))
            }
            "remove", "delete" -> confirmRemoved(parsed.args.getOrNull(0) ?: return)
            else -> confirmSnapshot(parsed.args.getOrNull(0) ?: return)
        }
    }

    private fun confirmSnapshot(name: String) {
        val snapshot = ResidenceHook.toSnapshot(name) ?: return
        runAsync { database.upsertSnapshot(snapshot) }
    }

    private fun confirmRemoved(name: String) {
        if (ResidenceHook.exists(name)) {
            return
        }
        runAsync { database.delete(name) }
    }

    private fun localTeleport(player: Player, residenceName: String) {
        val ok = ResidenceHook.teleport(player, residenceName)
        if (!ok) {
            player.sendBridgeMessage(config.messages.localTeleportFailed)
        }
    }

    private fun connectToServer(player: Player, serverId: String, message: String) {
        val ok = messenger.requestConnect(player, serverId)
        if (ok) {
            player.sendMessage(MessageUtil.apply(message, mapOf("server" to serverId)))
        } else {
            player.sendMessage(config.messages.connectRequestFailed)
        }
    }

    private fun scheduleSync() {
        syncTask = BridgeScheduler.runGlobalTimer(config.syncInitialDelayTicks, config.syncIntervalSeconds * 20L) {
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
        }
    }

    private fun runAsync(block: () -> Unit) {
        BridgeScheduler.runAsync(block)
    }

    private fun runPlayer(player: Player, delay: Long = 0L, block: () -> Unit): BridgeTask {
        return BridgeScheduler.runPlayer(player, delay) {
            try {
                block()
            } catch (t: Throwable) {
                warning("Player task failed: ${t.message}")
                t.printStackTrace()
            }
        }
    }

    private fun Player.sendBridgeMessage(message: String) {
        runPlayer(this) { sendMessage(message) }
    }

    private fun formatMax(max: Int): String = if (max == Int.MAX_VALUE) "无限" else max.toString()

    private data class WaitingTeleport(
        val entry: ResidenceIndexEntry,
        val worldName: String?,
        val x: Int,
        val y: Int,
        val z: Int,
        var task: BridgeTask? = null
    )

    private data class ParsedResidenceCommand(val subCommand: String, val args: List<String>, val rawCommand: String)

    private fun parseResidenceCommand(message: String): ParsedResidenceCommand? {
        val rawCommand = message.removePrefix("/").trim()
        val parts = rawCommand.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size < 2) {
            return null
        }
        val root = parts[0].lowercase(Locale.ROOT)
        if (root != "res" && root != "residence") {
            return null
        }
        return ParsedResidenceCommand(parts[1].lowercase(Locale.ROOT), parts.drop(2), rawCommand)
    }
}