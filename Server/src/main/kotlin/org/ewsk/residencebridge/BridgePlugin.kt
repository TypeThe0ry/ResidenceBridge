package org.ewsk.residencebridge

import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.EventPriority as BukkitEventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.TabCompleteEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning
import taboolib.platform.BukkitPlugin
import java.util.Collections
import java.util.WeakHashMap
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
    private val pendingRemovals = ConcurrentHashMap<UUID, MutableSet<String>>()
    private val localDeleteTombstones = ConcurrentHashMap.newKeySet<String>()
    private val waitingTeleports = ConcurrentHashMap<UUID, WaitingTeleport>()
    private val pendingArrivalTeleports = ConcurrentHashMap<UUID, String>()
    private val residenceEventListener = object : Listener {}
    private val commandOverrideListener = object : Listener {}
    private val handledCommandEvents = Collections.synchronizedMap(WeakHashMap<PlayerCommandPreprocessEvent, Boolean>())
    private val originalResidenceCommands = ConcurrentHashMap<String, Command>()
    private var residenceEventsRegistered = false
    private var commandOverrideRegistered = false
    private var commandMapOverrideRegistered = false
    private const val arrivalTeleportDelayTicks = 5L
    @Volatile
    private var residenceCompletionNames: List<String> = emptyList()
    @Volatile
    private var residenceCompletionEntries: List<ResidenceIndexEntry> = emptyList()
    @Volatile
    private var ownerCompletionNames: List<String> = emptyList()

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

    fun syncNow(callback: (Int, Throwable?) -> Unit) {
        if (!::database.isInitialized) {
            callback(0, IllegalStateException("ResidenceBridge database is not initialized."))
            return
        }
        BridgeScheduler.runGlobal {
            val snapshots = localSnapshots()
            runAsync {
                try {
                    database.syncServerSnapshots(snapshots)
                    refreshCompletionCache()
                    callback(snapshots.size, null)
                } catch (t: Throwable) {
                    callback(0, t)
                }
            }
        }
    }

    private fun start() {
        BridgeScheduler.init(plugin)
        config = BridgeConfig.load(plugin.config)
        database = BridgeDatabase(config)
        database.initTables()
        messenger = VelocityMessenger(plugin, config)
        messenger.register()
        registerCommandMapOverride()
        registerCommandOverride()
        registerResidenceEvents()
        PlaceholderBridge.register(config, database)
        scheduleSync()
        refreshCompletionCache()
    }

    private fun stop() {
        syncTask?.cancel()
        syncTask = null
        waitingTeleports.values.forEach { it.cancelTasks() }
        waitingTeleports.clear()
        pendingRemovals.clear()
        localDeleteTombstones.clear()
        residenceCompletionNames = emptyList()
        residenceCompletionEntries = emptyList()
        ownerCompletionNames = emptyList()
        handledCommandEvents.clear()
        restoreCommandMapOverride()
        HandlerList.unregisterAll(commandOverrideListener)
        commandOverrideRegistered = false
        HandlerList.unregisterAll(residenceEventListener)
        residenceEventsRegistered = false
        PlaceholderBridge.unregister()
        if (::messenger.isInitialized) {
            messenger.unregister()
        }
        if (::database.isInitialized) {
            database.close()
        }
        BridgeScheduler.shutdown()
    }

    fun onCommand(event: PlayerCommandPreprocessEvent) {
        handleCommandOverride(event)
    }

    private fun handleCommandOverride(event: PlayerCommandPreprocessEvent) {
        if (handledCommandEvents.containsKey(event)) {
            return
        }
        if (bypassCommand.remove(event.player.uniqueId)) {
            handledCommandEvents[event] = true
            return
        }
        val parsed = parseResidenceCommand(event.message) ?: return
        handledCommandEvents[event] = true
        if (parsed.subCommand == "confirm") {
            handleConfirm(event)
            return
        }
        if (parsed.admin) {
            handleAdminCommand(event, parsed)
            return
        }
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

    private fun handleCommandFromCommandMap(player: Player, label: String, args: Array<out String>): Boolean {
        if (bypassCommand.remove(player.uniqueId)) {
            return false
        }
        val commandLine = (listOf(label) + args).joinToString(" ")
        val event = PlayerCommandPreprocessEvent(player, "/$commandLine")
        handleCommandOverride(event)
        return event.isCancelled
    }

    private fun tabCompleteFromCommandMap(sender: CommandSender, args: Array<out String>): MutableList<String>? {
        val player = sender as? Player ?: return null
        if (args.isEmpty()) {
            return null
        }
        val subCommand = args[0].lowercase(Locale.ROOT)
        val argIndex = args.size - 2
        if (argIndex < 0) {
            return null
        }
        val currentArg = args.lastOrNull().orEmpty()
        val suggestions = when (subCommand) {
            "tp", "teleport", "remove", "delete", "rename", "give", "setowner" -> {
                if (argIndex == 0) complete(completionNamesFor(player), currentArg) else emptyList()
            }
            "list" -> {
                if (argIndex == 0) {
                    val values = if (player.canListOthers()) ownerCompletionNames else completionNamesFor(player)
                    complete(values, currentArg)
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
        return suggestions.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).toMutableList()
    }

    @SubscribeEvent
    fun onTabComplete(event: TabCompleteEvent) {
        if (!event.buffer.startsWith("/")) {
            return
        }
        val sender = event.sender as? Player ?: return
        val parsed = parseTabBuffer(event.buffer) ?: return
        val handled = parsed.argIndex == 0 && when (parsed.subCommand) {
            "tp", "teleport", "remove", "delete", "rename", "give", "setowner", "list" -> true
            else -> false
        }
        val suggestions = when (parsed.subCommand) {
            "tp", "teleport", "remove", "delete", "rename", "give", "setowner" -> {
                if (parsed.argIndex == 0) complete(completionNamesFor(sender), parsed.currentArg) else emptyList()
            }
            "list" -> {
                if (parsed.argIndex == 0 && sender.canListOthers()) complete(ownerCompletionNames, parsed.currentArg) else emptyList()
            }
            else -> emptyList()
        }
        if (handled) {
            event.completions = suggestions.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
    }

    @SubscribeEvent
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val uuid = player.uniqueId
        runAsync {
            val pendingTeleport = database.consumePending(uuid)
            if (pendingTeleport != null) {
                scheduleNativeArrivalTeleport(player, pendingTeleport.residenceName)
            }
        }
        runAsync {
            val pendingActions = database.consumePendingActions(uuid)
            if (pendingActions.isEmpty()) {
                return@runAsync
            }
            runPlayer(player, config.joinDelayTicks) {
                pendingActions.forEach { executePendingAction(player, it) }
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
        waitingTeleports.remove(event.player.uniqueId)?.cancelTasks()
        pendingArrivalTeleports.remove(event.player.uniqueId)
    }

    private fun handleList(event: PlayerCommandPreprocessEvent, parsed: ParsedResidenceCommand) {
        val player = event.player
        val firstArgPage = parsed.args.firstOrNull()?.toIntOrNull()
        val targetOwner = if (firstArgPage == null) parsed.args.firstOrNull()?.takeIf { it.isNotBlank() } else null
        val page = firstArgPage ?: parsed.args.getOrNull(1)?.toIntOrNull() ?: 1
        event.isCancelled = true
        if (targetOwner != null && !player.canListOthers()) {
            player.sendBridgeMessage(config.messages.noPermission)
            return
        }
        runAsync {
            val result = if (targetOwner == null) {
                database.listResidencesByOwner(player.uniqueId, player.name, page, config.list.pageSize)
            } else {
                database.listResidencesByOwnerName(targetOwner, page, config.list.pageSize)
            }
            runPlayer(player) {
                if (result.total <= 0) {
                    player.sendMessage(config.list.empty)
                    return@runPlayer
                }
                player.sendMessage(
                    MessageUtil.apply(
                        if (targetOwner == null) config.list.header else config.list.otherHeader,
                        mapOf("count" to result.total, "page" to result.page, "max_page" to result.maxPage, "target" to (targetOwner ?: player.name))
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
            if (database.hasCreateConflict(residenceName)) {
                player.sendBridgeMessage(MessageUtil.apply(config.messages.duplicate, mapOf("name" to residenceName)))
                return@runAsync
            }
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
                    scheduleCreateChecks(player, residenceName)
                }
            }
        }
    }

    private fun handleAdminCommand(event: PlayerCommandPreprocessEvent, parsed: ParsedResidenceCommand) {
        when (parsed.subCommand) {
            "create" -> parsed.args.getOrNull(0)?.let {
                scheduleCreateChecks(event.player, it)
            }
            "remove", "delete" -> parsed.args.getOrNull(0)?.let {
                trackRemoval(event.player, it)
                scheduleRemovalChecks(event.player, listOf(it))
            }
            "rename" -> {
                val oldName = parsed.args.getOrNull(0) ?: return
                val newName = parsed.args.getOrNull(1) ?: return
                scheduleRenameChecks(event.player, oldName, newName, key(oldName) == key(newName))
            }
        }
    }

    private fun handleConfirm(event: PlayerCommandPreprocessEvent) {
        val names = pendingRemovals[event.player.uniqueId]?.toList().orEmpty()
        if (names.isNotEmpty()) {
            scheduleRemovalChecks(event.player, names)
        }
    }

    private fun scheduleCreateChecks(player: Player, name: String) {
        listOf(20L, 40L, 100L, 200L, 600L, 1200L).forEach { delay ->
            runPlayer(player, delay) { confirmCreated(name, rollbackIfMissing = false) }
        }
        runPlayer(player, 2400L) { confirmCreated(name, rollbackIfMissing = true) }
    }

    private fun confirmCreated(name: String, rollbackIfMissing: Boolean) {
        val snapshot = ResidenceHook.toSnapshot(name)
        runAsync {
            if (snapshot != null) {
                localDeleteTombstones.remove(snapshot.nameKey)
                addCompletion(snapshot.name, snapshot.ownerUuid, snapshot.ownerName)
                database.upsertSnapshot(snapshot)
            } else if (rollbackIfMissing) {
                removeCompletion(name)
                database.deleteReservationIfLocal(name)
            }
        }
    }

    private fun handleTeleport(event: PlayerCommandPreprocessEvent, name: String?) {
        val player = event.player
        val residenceName = name?.takeIf { it.isNotBlank() } ?: return
        event.isCancelled = true
        val localSnapshot = ResidenceHook.toSnapshot(residenceName)
        if (localSnapshot != null) {
            startTeleport(
                player,
                ResidenceIndexEntry(
                    nameKey = localSnapshot.nameKey,
                    displayName = localSnapshot.name,
                    serverId = config.serverId,
                    worldName = localSnapshot.worldName,
                    ownerUuid = localSnapshot.ownerUuid,
                    ownerName = localSnapshot.ownerName,
                    updatedAt = System.currentTimeMillis(),
                    teleportLocation = localSnapshot.teleportLocation
                )
            )
            runAsync { database.upsertSnapshot(localSnapshot) }
            return
        }
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
        val current = waitingTeleports[player.uniqueId]
        if (current?.entry?.nameKey == entry.nameKey) {
            return
        }
        val waiting = WaitingTeleport(entry, location.world?.name, location.blockX, location.blockY, location.blockZ)
        waitingTeleports.remove(player.uniqueId)?.cancelTasks()
        waitingTeleports[player.uniqueId] = waiting
        for (remaining in seconds downTo 1) {
            val delay = (seconds - remaining) * 20L
            waiting.tasks += runPlayer(player, delay) {
                if (waitingTeleports[player.uniqueId] === waiting) {
                    player.sendMessage(MessageUtil.apply(config.messages.teleportWait, mapOf("seconds" to remaining, "name" to entry.displayName)))
                    playCountdownSound(player)
                }
            }
        }
        waiting.tasks += runPlayer(player, seconds * 20L) {
            val active = waitingTeleports.remove(player.uniqueId) ?: return@runPlayer
            if (active === waiting) {
                active.cancelTasks()
                executeTeleport(player, entry)
            }
        }
    }

    private fun cancelWaitingTeleport(player: Player) {
        waitingTeleports.remove(player.uniqueId)?.cancelTasks() ?: return
        player.sendMessage(config.messages.teleportCancelled)
    }

    private fun executeTeleport(player: Player, entry: ResidenceIndexEntry) {
        if (entry.serverId.equals(config.serverId, ignoreCase = true)) {
            runNativeResidenceTeleport(player, entry.displayName)
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
                    scheduleRenameChecks(player, oldName, newName, sameNameKey)
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
                if (!sameNameKey) {
                    markLocalDeleted(oldName)
                }
                localDeleteTombstones.remove(newSnapshot.nameKey)
                database.replaceRenamed(oldName, newSnapshot)
            } else if (!sameNameKey) {
                database.deleteReservationIfLocal(newName)
            }
        }
    }

    private fun handleRemove(event: PlayerCommandPreprocessEvent, parsed: ParsedResidenceCommand) {
        val residenceName = parsed.args.getOrNull(0) ?: return
        if (ResidenceHook.exists(residenceName)) {
            trackRemoval(event.player, residenceName)
            scheduleRemovalChecks(event.player, listOf(residenceName))
            return
        }
        handleRemoteAction(event, parsed, residenceName)
    }

    private fun handleRemoteAction(event: PlayerCommandPreprocessEvent, parsed: ParsedResidenceCommand, residenceName: String?) {
        val player = event.player
        val targetResidence = residenceName ?: return
        if (ResidenceHook.exists(targetResidence)) {
            if (parsed.subCommand == "remove" || parsed.subCommand == "delete") {
                trackRemoval(player, targetResidence)
                scheduleRemovalChecks(player, listOf(targetResidence))
            } else {
                runPlayer(player, 40L) { confirmActionSnapshot(parsed) }
            }
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

    private fun scheduleRenameChecks(player: Player, oldName: String, newName: String, sameNameKey: Boolean) {
        listOf(40L, 100L, 200L).forEach { delay ->
            runPlayer(player, delay) { confirmRenamed(oldName, newName, sameNameKey) }
        }
    }

    private fun confirmSnapshot(name: String) {
        val snapshot = ResidenceHook.toSnapshot(name) ?: return
        runAsync {
            localDeleteTombstones.remove(snapshot.nameKey)
            database.upsertSnapshot(snapshot)
        }
    }

    private fun trackRemoval(player: Player, name: String) {
        pendingRemovals.computeIfAbsent(player.uniqueId) {
            Collections.synchronizedSet(mutableSetOf())
        }.add(name)
    }

    private fun scheduleRemovalChecks(player: Player, names: List<String>) {
        listOf(20L, 60L, 120L, 240L).forEach { delay ->
            runPlayer(player, delay) {
                names.forEach { name ->
                    if (confirmRemoved(name)) {
                        pendingRemovals[player.uniqueId]?.remove(name)
                    }
                }
            }
        }
    }

    private fun confirmRemoved(name: String): Boolean {
        if (ResidenceHook.exists(name)) {
            return false
        }
        markLocalDeleted(name)
        runAsync { database.delete(name) }
        return true
    }

    private fun runNativeResidenceTeleport(player: Player, residenceName: String) {
        bypassCommand.add(player.uniqueId)
        player.performCommand("res tp $residenceName")
    }

    private fun scheduleNativeArrivalTeleport(player: Player, residenceName: String) {
        val nameKey = key(residenceName)
        pendingArrivalTeleports[player.uniqueId] = nameKey
        runPlayer(player, arrivalTeleportDelayTicks) {
            if (!player.isOnline || pendingArrivalTeleports[player.uniqueId] != nameKey) {
                return@runPlayer
            }
            pendingArrivalTeleports.remove(player.uniqueId, nameKey)
            runNativeResidenceTeleport(player, residenceName)
        }
    }

    private fun String.toLocalEntry(): ResidenceIndexEntry {
        return ResidenceIndexEntry(
            nameKey = key(this),
            displayName = this,
            serverId = config.serverId,
            worldName = null,
            ownerUuid = null,
            ownerName = null,
            updatedAt = System.currentTimeMillis()
        )
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
            val snapshots = localSnapshots()
            runAsync {
                try {
                    database.syncServerSnapshots(snapshots)
                    refreshCompletionCache()
                    if (config.syncLogSuccess || snapshots.isEmpty()) {
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

    private fun localSnapshots(): List<ResidenceSnapshot> {
        return ResidenceHook.allSnapshots().filter { snapshot -> snapshot.nameKey !in localDeleteTombstones }
    }

    private fun markLocalDeleted(name: String) {
        localDeleteTombstones.add(key(name))
        removeCompletion(name)
    }

    private fun refreshCompletionCache() {
        if (!::database.isInitialized) {
            return
        }
        runAsync {
            try {
                residenceCompletionNames = database.listCompletionResidenceNames()
                residenceCompletionEntries = database.listCompletionResidences()
                ownerCompletionNames = database.listCompletionOwnerNames()
            } catch (t: Throwable) {
                warning("Residence completion refresh failed: ${t.message}")
            }
        }
    }

    private fun addCompletion(residenceName: String, ownerUuid: UUID?, ownerName: String?) {
        residenceCompletionNames = (residenceCompletionNames + residenceName).distinctBy { key(it) }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        val entry = ResidenceIndexEntry(
            nameKey = key(residenceName),
            displayName = residenceName,
            serverId = config.serverId,
            worldName = null,
            ownerUuid = ownerUuid,
            ownerName = ownerName,
            updatedAt = System.currentTimeMillis()
        )
        residenceCompletionEntries = (residenceCompletionEntries.filterNot { it.nameKey == entry.nameKey } + entry)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
        if (!ownerName.isNullOrBlank()) {
            ownerCompletionNames = (ownerCompletionNames + ownerName).distinctBy { it.lowercase(Locale.ROOT) }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
    }

    private fun removeCompletion(residenceName: String) {
        val nameKey = key(residenceName)
        residenceCompletionNames = residenceCompletionNames.filterNot { key(it) == nameKey }
        residenceCompletionEntries = residenceCompletionEntries.filterNot { it.nameKey == nameKey }
    }

    private fun completionNamesFor(player: Player): List<String> {
        return residenceCompletionEntries.asSequence()
            .filter { entry ->
                entry.ownerUuid == player.uniqueId || entry.ownerName.equals(player.name, ignoreCase = true)
            }
            .map { it.displayName }
            .distinctBy { key(it) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()
    }

    private fun complete(values: List<String>, prefix: String): List<String> {
        return values.asSequence()
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .take(50)
            .toList()
    }

    private fun playCountdownSound(player: Player) {
        val soundName = config.teleportWait.countdownSound.trim()
        if (soundName.isEmpty()) {
            return
        }
        val sound = runCatching { Sound.valueOf(soundName.uppercase(Locale.ROOT)) }.getOrNull() ?: return
        player.playSound(player.location, sound, config.teleportWait.countdownSoundVolume, config.teleportWait.countdownSoundPitch)
    }

    private fun registerCommandMapOverride() {
        if (commandMapOverrideRegistered) {
            return
        }
        val commands = knownCommands() ?: return
        listOf(
            "res",
            "residence",
            "resadmin",
            "residenceadmin",
            "residence:res",
            "residence:residence",
            "residence:resadmin",
            "residence:residenceadmin"
        ).forEach { commandName ->
            val original = commands[commandName] ?: return@forEach
            if (original is ResidenceBridgeCommandWrapper) {
                return@forEach
            }
            originalResidenceCommands.putIfAbsent(commandName, original)
            commands[commandName] = ResidenceBridgeCommandWrapper(commandName.substringAfter(':'), original)
        }
        commandMapOverrideRegistered = true
    }

    private fun restoreCommandMapOverride() {
        val commands = knownCommands() ?: run {
            originalResidenceCommands.clear()
            commandMapOverrideRegistered = false
            return
        }
        originalResidenceCommands.forEach { (commandName, original) ->
            if (commands[commandName] is ResidenceBridgeCommandWrapper) {
                commands[commandName] = original
            }
        }
        originalResidenceCommands.clear()
        commandMapOverrideRegistered = false
    }

    @Suppress("UNCHECKED_CAST")
    private fun knownCommands(): MutableMap<String, Command>? {
        val commandMap = runCatching {
            Bukkit.getServer().javaClass.getMethod("getCommandMap").invoke(Bukkit.getServer())
        }.getOrNull() ?: return null
        var current: Class<*>? = commandMap.javaClass
        while (current != null) {
            val field = runCatching { current.getDeclaredField("knownCommands") }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(commandMap) as? MutableMap<String, Command>
                }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    private fun registerCommandOverride() {
        if (commandOverrideRegistered) {
            return
        }
        val executor = EventExecutor { _, event -> handleCommandOverride(event as PlayerCommandPreprocessEvent) }
        Bukkit.getPluginManager().registerEvent(
            PlayerCommandPreprocessEvent::class.java,
            commandOverrideListener,
            BukkitEventPriority.LOWEST,
            executor,
            plugin,
            false
        )
        Bukkit.getPluginManager().registerEvent(
            PlayerCommandPreprocessEvent::class.java,
            commandOverrideListener,
            BukkitEventPriority.HIGHEST,
            executor,
            plugin,
            false
        )
        commandOverrideRegistered = true
    }

    @Suppress("UNCHECKED_CAST")
    private fun registerResidenceEvents() {
        if (residenceEventsRegistered) {
            return
        }
        val residencePlugin = Bukkit.getPluginManager().getPlugin("Residence") ?: return
        val loader = residencePlugin.javaClass.classLoader
        val eventNames = listOf(
            "com.bekvon.bukkit.residence.event.ResidenceCreationEvent",
            "com.bekvon.bukkit.residence.event.ResidenceDeleteEvent",
            "com.bekvon.bukkit.residence.event.ResidenceRenameEvent",
            "com.bekvon.bukkit.residence.event.ResidenceOwnerChangeEvent"
        )
        eventNames.forEach { className ->
            val eventClass = runCatching { loader.loadClass(className) as Class<out Event> }.getOrNull() ?: return@forEach
            Bukkit.getPluginManager().registerEvent(
                eventClass,
                residenceEventListener,
                BukkitEventPriority.MONITOR,
                EventExecutor { _, event -> handleResidenceEvent(event) },
                plugin,
                true
            )
        }
        residenceEventsRegistered = true
    }

    private fun handleResidenceEvent(event: Event) {
        when (event.javaClass.name.substringAfterLast('.')) {
            "ResidenceCreationEvent" -> {
                val name = event.invokeNoArg("getResidenceName")?.toString()
                val snapshot = ResidenceHook.snapshotFromResidence(event.invokeNoArg("getResidence"), name)
                    ?: name?.let { ResidenceHook.toSnapshot(it) }
                    ?: return
                localDeleteTombstones.remove(snapshot.nameKey)
                addCompletion(snapshot.name, snapshot.ownerUuid, snapshot.ownerName)
                runAsync { database.upsertSnapshot(snapshot) }
                name?.let { createdName ->
                    runPlayer(snapshot.ownerUuid?.let { Bukkit.getPlayer(it) } ?: return@let, 20L) {
                        confirmCreated(createdName, rollbackIfMissing = false)
                    }
                }
            }
            "ResidenceDeleteEvent" -> {
                val residence = event.invokeNoArg("getResidence")
                val name = ResidenceHook.snapshotFromResidence(residence)?.name ?: residence?.invokeNoArg("getName")?.toString() ?: return
                markLocalDeleted(name)
                runAsync { database.delete(name) }
            }
            "ResidenceRenameEvent" -> {
                val oldName = event.invokeNoArg("getOldResidenceName")?.toString() ?: return
                val newName = event.invokeNoArg("getNewResidenceName")?.toString() ?: return
                val snapshot = ResidenceHook.snapshotFromResidence(event.invokeNoArg("getResidence"), newName)
                    ?: ResidenceHook.toSnapshot(newName)
                    ?: return
                if (key(oldName) != snapshot.nameKey) {
                    markLocalDeleted(oldName)
                }
                localDeleteTombstones.remove(snapshot.nameKey)
                addCompletion(snapshot.name, snapshot.ownerUuid, snapshot.ownerName)
                runAsync { database.replaceRenamed(oldName, snapshot) }
            }
            "ResidenceOwnerChangeEvent" -> {
                val snapshot = ResidenceHook.snapshotFromResidence(event.invokeNoArg("getResidence")) ?: return
                localDeleteTombstones.remove(snapshot.nameKey)
                addCompletion(snapshot.name, snapshot.ownerUuid, snapshot.ownerName)
                runAsync { database.upsertSnapshot(snapshot) }
            }
        }
    }

    private fun Any.invokeNoArg(name: String): Any? {
        var current: Class<*>? = javaClass
        while (current != null) {
            val method = runCatching { current.getDeclaredMethod(name) }.getOrNull()
            if (method != null) {
                return runCatching {
                    method.isAccessible = true
                    method.invoke(this)
                }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    private fun runPlayer(player: Player, delay: Long = 0L, block: () -> Unit): BridgeTask {
        return BridgeScheduler.runPlayer(player, delay) {
            try {
                block()
            } catch (t: Throwable) {
                warning("Player task failed: ${t.message}")
            }
        }
    }

    private fun Player.sendBridgeMessage(message: String) {
        runPlayer(this) { sendMessage(message) }
    }

    private fun Player.canListOthers(): Boolean {
        return isOp || hasPermission(config.list.othersPermission) || hasPermission("residencebridge.admin")
    }

    private fun formatMax(max: Int): String = if (max == Int.MAX_VALUE) "无限" else max.toString()

    private data class WaitingTeleport(
        val entry: ResidenceIndexEntry,
        val worldName: String?,
        val x: Int,
        val y: Int,
        val z: Int,
        val tasks: MutableList<BridgeTask> = mutableListOf()
    ) {
        fun cancelTasks() {
            tasks.forEach { it.cancel() }
            tasks.clear()
        }
    }

    private data class ParsedResidenceCommand(val root: String, val subCommand: String, val args: List<String>, val rawCommand: String) {
        val admin: Boolean = root == "resadmin" || root == "residenceadmin"
    }

    private data class ParsedTabCommand(val subCommand: String, val argIndex: Int, val currentArg: String)

    private class ResidenceBridgeCommandWrapper(name: String, private val original: Command) : Command(name) {
        override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
            val player = sender as? Player
            if (player != null && handleCommandFromCommandMap(player, commandLabel, args)) {
                return true
            }
            return original.execute(sender, commandLabel, args)
        }

        override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): MutableList<String> {
            tabCompleteFromCommandMap(sender, args)?.let { return it }
            return runCatching { original.tabComplete(sender, alias, args) }.getOrElse { mutableListOf() }
        }
    }

    private fun parseResidenceCommand(message: String): ParsedResidenceCommand? {
        val rawCommand = message.removePrefix("/").trim()
        val parts = rawCommand.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size < 2) {
            return null
        }
        val root = parts[0].lowercase(Locale.ROOT)
        if (root != "res" && root != "residence" && root != "resadmin" && root != "residenceadmin") {
            return null
        }
        return ParsedResidenceCommand(root, parts[1].lowercase(Locale.ROOT), parts.drop(2), rawCommand)
    }

    private fun parseTabBuffer(buffer: String): ParsedTabCommand? {
        val rawCommand = buffer.removePrefix("/")
        val trailingSpace = rawCommand.lastOrNull()?.isWhitespace() == true
        val parts = rawCommand.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size < 2) {
            return null
        }
        val root = parts[0].lowercase(Locale.ROOT)
        if (root != "res" && root != "residence" && root != "resadmin" && root != "residenceadmin") {
            return null
        }
        val subCommand = parts[1].lowercase(Locale.ROOT)
        val args = parts.drop(2)
        val argIndex = if (trailingSpace) args.size else (args.size - 1).coerceAtLeast(0)
        val currentArg = if (trailingSpace || args.isEmpty()) "" else args.last()
        return ParsedTabCommand(subCommand, argIndex, currentArg)
    }
}