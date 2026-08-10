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
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.Collections
import java.util.TreeSet
import java.util.WeakHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

object BridgePlugin {

    private lateinit var plugin: Plugin
    private lateinit var config: BridgeConfig
    private lateinit var database: BridgeDatabase
    private lateinit var messenger: VelocityMessenger
    private lateinit var messages: MessageService
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
    private val teleportWaitListener = object : Listener {}
    private var teleportWaitListenerRegistered = false
    private val bridgeListener = object : Listener {}
    private var bridgeEventsRegistered = false
    private val handledCommandEvents = Collections.synchronizedMap(WeakHashMap<PlayerCommandPreprocessEvent, Boolean>())
    private val originalResidenceCommands = ConcurrentHashMap<String, Command>()
    private var residenceEventsRegistered = false
    private var commandOverrideRegistered = false
    private var commandMapOverrideRegistered = false
    private const val arrivalTeleportDelayTicks = 5L
    private const val COMPLETION_LIMIT = 50
    private val completionNamesByPlayer = ConcurrentHashMap<UUID, List<String>>()
    @Volatile
    private var lastCompletionVersion = -1L

    // ===== completion 索引 =====
    // 领地补全的唯一数据源：nameKey -> entry。add/remove 都是 O(1)，不再重建任何列表。
    // 每个玩家可见的名字由 completionNamesByPlayer 缓存（已排序），所以这里不需要维护全局顺序。
    private val completionEntriesByKey = ConcurrentHashMap<String, ResidenceIndexEntry>()
    // owner 补全需要按字母序输出，用 TreeSet 维护；读取时在锁内做前缀切片。
    private val completionOwnerOrder = TreeSet<String>(String.CASE_INSENSITIVE_ORDER)

    // ===== 增量同步指纹（nameKey -> 内容指纹），用于跳过未变化的快照写入 =====
    private val snapshotFingerprints = ConcurrentHashMap<String, Long>()

    // ===== 事件驱动写入的批量合并队列（定期 flush，减少零散连接） =====
    private val pendingSnapshotWrites = ConcurrentLinkedQueue<ResidenceSnapshot>()
    private val pendingDeletes = ConcurrentLinkedQueue<String>()
    private val writeFlushLock = Any()
    @Volatile
    private var writeFlusherScheduled = false

    fun enable(javaPlugin: JavaPlugin) {
        plugin = javaPlugin
        javaPlugin.saveDefaultConfig()
        start()
    }

    fun disable() {
        stop()
    }

    fun reload() {
        val loadedConfig = BridgeConfig.load(File(plugin.dataFolder, "config.yml"))
        messages.reload(loadedConfig.language)
        stop()
        config = loadedConfig
        start(configAlreadyLoaded = true)
    }

    fun send(sender: CommandSender, key: String, placeholders: Map<String, Any?> = emptyMap()) {
        messages.send(sender, key, placeholders)
    }

    fun syncNow(callback: (Int, Throwable?) -> Unit) {
        if (!::database.isInitialized) {
            callback(0, IllegalStateException("ResidenceBridge database is not initialized."))
            return
        }
        // 内存快照在主线程采集（Residence 容器非线程安全），文件解析与数据库写入在异步线程完成。
        collectSnapshotsThen { snapshots ->
            try {
                val data = localSyncData(snapshots)
                database.syncServerSnapshots(data.changed, data.knownKeys)
                refreshCompletionCache(async = false)
                runGlobal { callback(data.changed.size, null) }
            } catch (t: Throwable) {
                runGlobal { callback(0, t) }
            }
        }
    }

    private fun start(configAlreadyLoaded: Boolean = false) {
        BridgeScheduler.init(plugin)
        ResidenceHook.resetRuntimeCaches()
        ResidenceHook.warmUp()
        if (!configAlreadyLoaded) {
            config = BridgeConfig.load(File(plugin.dataFolder, "config.yml"))
            messages = MessageService.create(plugin as JavaPlugin)
            messages.initialize(config)
        }
        database = BridgeDatabase(config)
        database.initTables()
        messenger = VelocityMessenger(plugin, config)
        messenger.register()
        registerCommandMapOverride()
        registerCommandOverride()
        registerResidenceEvents()
        registerBridgeEvents()
        (plugin as? JavaPlugin)?.getCommand("rb")?.setExecutor(ResidenceBridgeCommand)
        PlaceholderBridge.register(plugin, config, database)
        scheduleSync()
        lastCompletionVersion = -1L
        refreshCompletionCache()
        // 预热存档文件索引：主线程的 exists()/toSnapshot() 只读缓存，冷缓存会让本服领地
        // 被误判成远程领地。这里在异步线程先填一次。
        runAsync {
            try {
                ResidenceHook.refreshFileSnapshots()
            } catch (t: Throwable) {
                plugin.logger.warning("Residence file snapshot warmup failed: ${t.message}")
            }
        }
    }

    private fun stop() {
        syncTask?.cancel()
        syncTask = null
        waitingTeleports.values.forEach { it.cancelTasks() }
        waitingTeleports.clear()
        pendingRemovals.clear()
        localDeleteTombstones.clear()
        completionEntriesByKey.clear()
        synchronized(completionOwnerOrder) { completionOwnerOrder.clear() }
        completionNamesByPlayer.clear()
        lastCompletionVersion = -1L
        snapshotFingerprints.clear()
        bypassCreate.clear()
        bypassRename.clear()
        bypassCommand.clear()
        pendingArrivalTeleports.clear()
        handledCommandEvents.clear()
        restoreCommandMapOverride()
        HandlerList.unregisterAll(commandOverrideListener)
        commandOverrideRegistered = false
        HandlerList.unregisterAll(residenceEventListener)
        residenceEventsRegistered = false
        HandlerList.unregisterAll(teleportWaitListener)
        teleportWaitListenerRegistered = false
        HandlerList.unregisterAll(bridgeListener)
        bridgeEventsRegistered = false
        PlaceholderBridge.unregister()
        if (::messenger.isInitialized) {
            messenger.unregister()
        }
        // 顺序很重要：先等在途异步任务结束（它们可能正在写库），再 flush 残留队列，
        // 最后才关闭连接池。之前 shutdown() 排在 database.close() 之后，
        // 迟到的异步写入会拿到已关闭的数据源。
        BridgeScheduler.shutdown()
        if (::database.isInitialized) {
            try {
                flushPendingWritesSynchronously()
            } finally {
                database.close()
            }
        }
    }

    fun onCommand(event: PlayerCommandPreprocessEvent) {
        handleCommandOverride(event)
    }

    private fun handleCommandOverride(event: PlayerCommandPreprocessEvent) {
        // 先做最便宜的前缀判断：绝大多数命令不是 res 系列，直接放行，避免为每条聊天命令
        // 触碰 WeakHashMap（需要全局同步）和解析器。
        if (!isResidenceCommandMessage(event.message)) {
            return
        }
        if (handledCommandEvents.containsKey(event)) {
            return
        }
        // bypass 令牌只能被 res 系列命令消费。之前放在解析之前，导致玩家在 bypass 生效期间
        // 随便发一条别的命令就会吃掉令牌，随后 res 命令重新被拦截 -> 递归执行或直接失效。
        val parsed = parseResidenceCommand(event.message) ?: return
        if (bypassCommand.remove(event.player.uniqueId)) {
            handledCommandEvents[event] = true
            return
        }
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
        // 这里不再自行消费 bypass 令牌：交给 handleCommandOverride 在确认是 res 命令后统一处理，
        // 否则 commandMap 与事件两条入口会各消费一次，令牌提前耗尽。
        val commandLine = buildString(label.length + args.sumOf { it.length + 1 } + 1) {
            append('/').append(label)
            args.forEach { append(' ').append(it) }
        }
        val event = PlayerCommandPreprocessEvent(player, commandLine)
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
                    if (player.canListOthers()) completeOwnerNames(currentArg) else complete(completionNamesFor(player), currentArg)
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
        return suggestions.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).toMutableList()
    }

    private fun onTabComplete(event: TabCompleteEvent) {
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
                if (parsed.argIndex == 0 && sender.canListOthers()) completeOwnerNames(parsed.currentArg) else emptyList()
            }
            else -> emptyList()
        }
        if (handled) {
            event.completions = combineTabCompletions(parsed.subCommand, event.completions, suggestions)
        }
    }

    private fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val uuid = player.uniqueId
        runAsync {
            val data = database.consumePendingForJoin(uuid)
            data.pendingTeleport?.let { scheduleNativeArrivalTeleport(player, it.residenceName) }
            if (data.pendingActions.isNotEmpty()) {
                runPlayer(player, config.joinDelayTicks) {
                    data.pendingActions.forEach { executePendingAction(player, it) }
                }
            }
        }
    }

    private fun handleMove(event: PlayerMoveEvent) {
        if (waitingTeleports.isEmpty()) return
        val waiting = waitingTeleports[event.player.uniqueId] ?: return
        val to = event.to ?: return
        if (waiting.worldName != to.world?.name || waiting.x != to.blockX || waiting.y != to.blockY || waiting.z != to.blockZ) {
            cancelWaitingTeleport(event.player)
        }
    }

    private fun handleDamage(event: EntityDamageEvent) {
        if (waitingTeleports.isEmpty()) return
        val player = event.entity as? Player ?: return
        if (waitingTeleports.containsKey(player.uniqueId)) {
            cancelWaitingTeleport(player)
        }
    }

    private fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        waitingTeleports.remove(uuid)?.cancelTasks()
        pendingArrivalTeleports.remove(uuid)
        // 这些 map 之前只增不减，玩家反复进出会持续堆积 UUID 与名字列表。
        pendingRemovals.remove(uuid)
        completionNamesByPlayer.remove(uuid)
        bypassCreate.remove(uuid)
        bypassRename.remove(uuid)
        bypassCommand.remove(uuid)
        unregisterTeleportWaitListenerIfEmpty()
    }

    private fun handleList(event: PlayerCommandPreprocessEvent, parsed: ParsedResidenceCommand) {
        val player = event.player
        val firstArgPage = parsed.args.firstOrNull()?.toIntOrNull()
        val targetOwner = if (firstArgPage == null) parsed.args.firstOrNull()?.takeIf { it.isNotBlank() } else null
        val page = firstArgPage ?: parsed.args.getOrNull(1)?.toIntOrNull() ?: 1
        event.isCancelled = true
        if (targetOwner != null && !player.canListOthers()) {
            player.sendBridgeMessage("command.no-permission")
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
                    messages.send(player, "list.empty")
                    return@runPlayer
                }
                messages.send(
                    player,
                    if (targetOwner == null) "list.header" else "list.other-header",
                    mapOf("count" to result.total, "page" to result.page, "max_page" to result.maxPage, "target" to (targetOwner ?: player.name))
                )
                result.entries.forEachIndexed { index, entry ->
                    messages.send(
                        player,
                        "list.line",
                        mapOf(
                            "index" to ((result.page - 1) * result.pageSize + index + 1),
                            "name" to entry.displayName,
                            "server" to entry.serverId,
                            "world" to entry.worldName,
                            "owner" to entry.ownerName
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
                player.sendBridgeMessage("residence.duplicate", mapOf("name" to residenceName))
                return@runAsync
            }
            val reserved = database.tryReserveCreate(residenceName, player.uniqueId, player.name, maxResidences)
            when (reserved.status) {
                CreateReservationStatus.DUPLICATE -> player.sendBridgeMessage(
                    "residence.duplicate", mapOf("name" to residenceName)
                )
                CreateReservationStatus.LIMIT_REACHED -> player.sendBridgeMessage(
                    "residence.limit-reached",
                    mapOf("count" to reserved.count, "max" to formatMax(player, reserved.max))
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
        val local = ResidenceHook.toSnapshot(name)
        runAsync {
            if (local != null) {
                // 传送点只存在于 Residence 的存档文件里，补齐动作放在异步线程。
                val snapshot = ResidenceHook.withFileTeleportLocation(local)
                localDeleteTombstones.remove(snapshot.nameKey)
                addCompletion(snapshot.name, snapshot.ownerUuid, snapshot.ownerName)
                enqueueWrite(snapshot)
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
            return
        }
        runAsync {
            val entry = database.findIndex(residenceName)
            if (entry == null) {
                player.sendBridgeMessage("residence.not-found", mapOf("name" to residenceName))
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
        // 已经在等待同一个领地：保持原倒计时不变。调用方已 cancel 掉命令事件，
        // 这里必须重发一次提示，否则玩家重复输入命令时什么反馈都看不到。
        val current = waitingTeleports[player.uniqueId]
        if (current != null && current.entry.nameKey == entry.nameKey) {
            messages.send(player, "teleport.wait", mapOf("seconds" to seconds, "name" to entry.displayName))
            return
        }
        val waiting = WaitingTeleport(entry, location.world?.name, location.blockX, location.blockY, location.blockZ)
        // 切换目标领地：先取消旧倒计时任务，再登记新的等待状态。
        waitingTeleports.put(player.uniqueId, waiting)?.cancelTasks()
        ensureTeleportWaitListenerRegistered()
        for (remaining in seconds downTo 1) {
            val delay = (seconds - remaining) * 20L
            waiting.addTask(
                runPlayer(player, delay) {
                    if (waitingTeleports[player.uniqueId] === waiting) {
                        messages.send(player, "teleport.wait", mapOf("seconds" to remaining, "name" to entry.displayName))
                        playCountdownSound(player)
                    }
                }
            )
        }
        waiting.addTask(
            runPlayer(player, seconds * 20L) {
                // 只有当前登记的仍是本次等待时才消费它。之前无条件 remove，
                // 若玩家中途切换了目标领地，这个迟到的任务会把新的等待状态删掉，
                // 导致新倒计时永远不会执行传送。
                if (!waitingTeleports.remove(player.uniqueId, waiting)) {
                    return@runPlayer
                }
                waiting.cancelTasks()
                executeTeleport(player, entry)
                unregisterTeleportWaitListenerIfEmpty()
            }
        )
    }

    private fun cancelWaitingTeleport(player: Player) {
        waitingTeleports.remove(player.uniqueId)?.cancelTasks() ?: return
        messages.send(player, "teleport.cancelled")
        unregisterTeleportWaitListenerIfEmpty()
    }

    private fun executeTeleport(player: Player, entry: ResidenceIndexEntry) {
        if (entry.serverId.equals(config.serverId, ignoreCase = true)) {
            runNativeResidenceTeleport(player, entry.displayName)
            return
        }
        checkServerThenConnect(player, entry.serverId, "teleport.switching") {
            val expireAt = System.currentTimeMillis() + config.pendingExpireSeconds * 1000L
            database.writePending(player.uniqueId, player.name, entry.displayName, entry.serverId, expireAt)
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
                        player.sendBridgeMessage("residence.duplicate", mapOf("name" to newName))
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
                player.sendBridgeMessage("residence.not-found", mapOf("name" to oldName))
                return@runAsync
            }
            if (entry.serverId.equals(config.serverId, ignoreCase = true)) {
                player.sendBridgeMessage("residence.not-found", mapOf("name" to oldName))
                return@runAsync
            }
            if (key(oldName) != key(newName)) {
                val reserved = database.reserveName(newName, entry.serverId, entry.ownerUuid, entry.ownerName)
                if (!reserved) {
                    player.sendBridgeMessage("residence.duplicate", mapOf("name" to newName))
                    return@runAsync
                }
            }
            queueRemoteAction(player, parsed, entry)
        }
    }

    private fun confirmRenamed(oldName: String, newName: String, sameNameKey: Boolean) {
        val localNew = ResidenceHook.toSnapshot(newName)
        val oldStillExists = if (sameNameKey) false else ResidenceHook.exists(oldName)
        runAsync {
            if (localNew != null && !oldStillExists) {
                val newSnapshot = ResidenceHook.withFileTeleportLocation(localNew)
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
                player.sendBridgeMessage("residence.not-found", mapOf("name" to targetResidence))
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
        checkServerThenConnect(player, entry.serverId, "remote.switching") {
            val expireAt = System.currentTimeMillis() + config.pendingExpireSeconds * 1000L
            database.writePendingAction(player.uniqueId, player.name, parsed.subCommand, parsed.rawCommand, entry.displayName, entry.serverId, expireAt)
        }
    }

    private fun executePendingAction(player: Player, action: PendingAction) {
        messages.send(player, "remote.queued")
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
        val local = ResidenceHook.toSnapshot(name) ?: return
        runAsync {
            val snapshot = ResidenceHook.withFileTeleportLocation(local)
            localDeleteTombstones.remove(snapshot.nameKey)
            enqueueWrite(snapshot)
        }
    }

    private fun trackRemoval(player: Player, name: String) {
        pendingRemovals.computeIfAbsent(player.uniqueId) {
            Collections.synchronizedSet(mutableSetOf())
        }.add(name)
    }

    private fun scheduleRemovalChecks(player: Player, names: List<String>) {
        if (names.isEmpty()) {
            return
        }
        val uuid = player.uniqueId
        listOf(20L, 60L, 120L, 240L).forEach { delay ->
            runPlayer(player, delay) {
                // 主线程先过滤：内存里还在的直接跳过，只把真正需要落盘确认的名字打包成
                // 一个异步任务。之前每个名字各自提交一次异步刷盘，4 个延迟档位叠加后
                // 一次删除最多触发 4×N 次全量扫盘。
                val candidates = names.filterNot { ResidenceHook.isLoaded(it) }
                if (candidates.isEmpty()) {
                    return@runPlayer
                }
                runAsync { confirmRemovedBatch(uuid, candidates) }
            }
        }
    }

    /**
     * 两阶段确认删除的第二阶段（异步线程）。
     * 内存索引里已经没有这些领地，但必须强制刷新一次存档文件才能下最终结论——
     * 否则当 Residence 反射失效（内存索引恒为空）且文件缓存尚未预热时，
     * 会把实际存在的领地误判为已删除，进而清掉数据库记录。
     *
     * 一次刷盘服务整批名字，刷新代价与名字数量无关。
     */
    private fun confirmRemovedBatch(playerUuid: UUID, names: List<String>) {
        ResidenceHook.refreshFileSnapshots()
        names.forEach { name ->
            if (ResidenceHook.existsInFiles(name)) {
                return@forEach
            }
            markLocalDeleted(name)
            enqueueDelete(name)
            pendingRemovals[playerUuid]?.remove(name)
        }
    }

    /**
     * 单个领地的删除确认（事件回调路径）。同样是两阶段，语义与 [confirmRemovedBatch] 一致。
     */
    private fun confirmRemoved(name: String) {
        if (ResidenceHook.isLoaded(name)) {
            return
        }
        runAsync {
            ResidenceHook.refreshFileSnapshots()
            if (ResidenceHook.existsInFiles(name)) {
                return@runAsync
            }
            markLocalDeleted(name)
            enqueueDelete(name)
        }
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

    private fun checkServerThenConnect(player: Player, serverId: String, messageKey: String, writePending: () -> Unit) {
        runPlayer(player) {
            messenger.checkAvailability(player, serverId) { availability ->
                runPlayer(player) {
                    if (!player.isOnline) return@runPlayer
                    when (availability) {
                        ServerAvailability.AVAILABLE -> runAsync {
                            try {
                                writePending()
                                runPlayer(player) {
                                    val sent = messenger.requestConnect(player, serverId) {
                                        runPlayer(player) { messages.send(player, "teleport.connect-failed") }
                                    }
                                    if (sent) {
                                        messages.send(player, messageKey, mapOf("server" to serverId))
                                    } else {
                                        messages.send(player, "teleport.connect-failed")
                                    }
                                }
                            } catch (t: Throwable) {
                                plugin.logger.warning("Failed to prepare cross-server request: ${t.message}")
                                player.sendBridgeMessage("teleport.connect-failed")
                            }
                        }
                        ServerAvailability.NOT_FOUND -> messages.send(player, "teleport.server-not-found", mapOf("server" to serverId))
                        ServerAvailability.OFFLINE -> messages.send(player, "teleport.server-offline", mapOf("server" to serverId))
                        ServerAvailability.UNAVAILABLE -> messages.send(player, "teleport.status-unavailable")
                    }
                }
            }
        }
    }

    private fun scheduleSync() {
        // 定时器本身跑在主线程（Folia 的 global region），这里只做内存快照采集，
        // 其余工作立刻转异步。
        syncTask = BridgeScheduler.runGlobalTimer(config.syncInitialDelayTicks, config.syncIntervalSeconds * 20L) {
            val snapshots = ResidenceHook.memorySnapshots()
            runAsync {
                try {
                    val data = localSyncData(snapshots)
                    database.syncServerSnapshots(data.changed, data.knownKeys)
                    refreshCompletionCache(async = false)
                    // 之前的条件写成了 `syncLogSuccess || changed.isEmpty()`，
                    // 结果在关闭日志时反而每个空闲周期都打印一行。
                    if (config.syncLogSuccess && data.changed.isNotEmpty()) {
                        plugin.logger.info(
                            "Synced ${data.changed.size} changed of ${data.knownKeys.size} residences for ${config.serverId}."
                        )
                    }
                } catch (t: Throwable) {
                    plugin.logger.warning("Residence sync failed: ${t.message}")
                }
            }
        }
    }

    private fun runAsync(block: () -> Unit) {
        BridgeScheduler.runAsync(block)
    }

    private fun runGlobal(delayTicks: Long = 0L, block: () -> Unit) {
        BridgeScheduler.runGlobal(delayTicks, block)
    }

    private data class LocalSyncData(
        val changed: List<ResidenceSnapshot>,
        val knownKeys: List<String>
    )

    /**
     * 必须在异步线程调用，且 [memorySnapshots] 必须是主线程采集的结果。
     * 文件解析与指纹比对都在这里完成，主线程只承担一次内存遍历。
     */
    private fun localSyncData(memorySnapshots: List<ResidenceSnapshot>): LocalSyncData {
        val merged = ResidenceHook.mergeFileTeleportLocations(memorySnapshots)
        val all = if (localDeleteTombstones.isEmpty()) {
            merged
        } else {
            merged.filter { snapshot -> snapshot.nameKey !in localDeleteTombstones }
        }
        val changed = ArrayList<ResidenceSnapshot>()
        val currentKeys = ArrayList<String>(all.size)
        val currentKeySet = HashSet<String>(all.size)
        for (snapshot in all) {
            currentKeys += snapshot.nameKey
            currentKeySet += snapshot.nameKey
            val fingerprint = fingerprint(snapshot)
            if (snapshotFingerprints.put(snapshot.nameKey, fingerprint) != fingerprint) {
                changed += snapshot
            }
        }
        snapshotFingerprints.keys.retainAll(currentKeySet)
        return LocalSyncData(changed, currentKeys)
    }

    /**
     * 在主线程采集 Residence 内存快照，然后把重活（文件解析 + 指纹 + 数据库）交给异步线程。
     */
    private fun collectSnapshotsThen(block: (List<ResidenceSnapshot>) -> Unit) {
        runGlobal {
            val snapshots = ResidenceHook.memorySnapshots()
            runAsync { block(snapshots) }
        }
    }

    private fun markLocalDeleted(name: String) {
        localDeleteTombstones.add(key(name))
        snapshotFingerprints.remove(key(name))
        removeCompletion(name)
    }

    private fun enqueueWrite(snapshot: ResidenceSnapshot) {
        pendingSnapshotWrites.add(snapshot)
        snapshotFingerprints[snapshot.nameKey] = fingerprint(snapshot)
        scheduleWriteFlush()
    }

    private fun enqueueDelete(name: String) {
        val nameKey = key(name)
        pendingDeletes.add(nameKey)
        snapshotFingerprints.remove(nameKey)
        scheduleWriteFlush()
    }

    private fun scheduleWriteFlush() {
        synchronized(writeFlushLock) {
            if (writeFlusherScheduled) {
                return
            }
            writeFlusherScheduled = true
        }
        BridgeScheduler.runGlobal(20L) {
            writeFlusherScheduled = false
            flushPendingWrites()
        }
    }

    private fun flushPendingWrites() {
        val snapshots = mutableListOf<ResidenceSnapshot>()
        while (true) {
            pendingSnapshotWrites.poll()?.let { snapshots += it } ?: break
        }
        val deletes = mutableListOf<String>()
        while (true) {
            pendingDeletes.poll()?.let { deletes += it } ?: break
        }
        if (snapshots.isEmpty() && deletes.isEmpty()) {
            return
        }
        runAsync {
            try {
                if (deletes.isNotEmpty()) {
                    database.bulkDelete(deletes.distinct())
                }
                if (snapshots.isNotEmpty()) {
                    database.bulkUpsertSnapshots(snapshots.distinctBy { it.nameKey })
                }
            } catch (t: Throwable) {
                plugin.logger.warning("Batched residence write failed: ${t.message}")
            }
        }
    }

    private fun flushPendingWritesSynchronously() {
        val snapshots = mutableListOf<ResidenceSnapshot>()
        while (true) {
            pendingSnapshotWrites.poll()?.let { snapshots += it } ?: break
        }
        val deletes = mutableListOf<String>()
        while (true) {
            pendingDeletes.poll()?.let { deletes += it } ?: break
        }
        if (snapshots.isEmpty() && deletes.isEmpty()) {
            return
        }
        try {
            if (deletes.isNotEmpty()) {
                database.bulkDelete(deletes.distinct())
            }
            if (snapshots.isNotEmpty()) {
                database.bulkUpsertSnapshots(snapshots.distinctBy { it.nameKey })
            }
        } catch (t: Throwable) {
            plugin.logger.warning("Batched residence write failed: ${t.message}")
        }
    }

    private fun refreshCompletionCache(async: Boolean = true) {
        if (!::database.isInitialized) {
            return
        }
        val action = {
            try {
                // 轻量版本对比：数据未变化（本服增量同步无写入 + 无跨服写入）时跳过全表查询
                val version = database.completionVersion()
                if (version != lastCompletionVersion) {
                    // 只查一次全表：owner 名单可以从同一批 entry 里推导，不必再发一条 DISTINCT 查询。
                    val entries = database.listCompletionResidences()
                    completionEntriesByKey.keys.retainAll(entries.mapTo(HashSet(entries.size)) { it.nameKey })
                    entries.forEach { completionEntriesByKey[it.nameKey] = it }
                    synchronized(completionOwnerOrder) {
                        completionOwnerOrder.clear()
                        entries.forEach { entry ->
                            entry.ownerName?.takeIf { it.isNotBlank() }?.let { completionOwnerOrder.add(it) }
                        }
                    }
                    completionNamesByPlayer.clear()
                    lastCompletionVersion = version
                }
            } catch (t: Throwable) {
                plugin.logger.warning("Residence completion refresh failed: ${t.message}")
            }
        }
        if (async) runAsync(action) else action()
    }

    private fun addCompletion(residenceName: String, ownerUuid: UUID?, ownerName: String?) {
        val nameKey = key(residenceName)
        completionEntriesByKey[nameKey] = ResidenceIndexEntry(
            nameKey = nameKey,
            displayName = residenceName,
            serverId = config.serverId,
            worldName = null,
            ownerUuid = ownerUuid,
            ownerName = ownerName,
            updatedAt = System.currentTimeMillis()
        )
        if (!ownerName.isNullOrBlank()) {
            synchronized(completionOwnerOrder) { completionOwnerOrder.add(ownerName) }
        }
        invalidatePlayerCompletions(nameKey, ownerUuid)
    }

    private fun removeCompletion(residenceName: String) {
        val nameKey = key(residenceName)
        val removed = completionEntriesByKey.remove(nameKey)
        invalidatePlayerCompletions(nameKey, removed?.ownerUuid)
    }

    /**
     * 失效受影响玩家的补全缓存。已知 owner 时走 O(1) 删除；
     * 否则才回落到扫描（此时用 nameKey 直接比对，不再对每个缓存名字重算 key()）。
     */
    private fun invalidatePlayerCompletions(nameKey: String, ownerUuid: UUID?) {
        if (ownerUuid != null) {
            completionNamesByPlayer.remove(ownerUuid)
            return
        }
        completionNamesByPlayer.entries.removeIf { cached ->
            cached.value.any { name -> key(name) == nameKey }
        }
    }

    private fun completionNamesFor(player: Player): List<String> {
        completionNamesByPlayer[player.uniqueId]?.let { return it }
        val uuid = player.uniqueId
        val name = player.name
        val matched = TreeSet<String>(String.CASE_INSENSITIVE_ORDER)
        completionEntriesByKey.values.forEach { entry ->
            if (entry.ownerUuid == uuid || entry.ownerName.equals(name, ignoreCase = true)) {
                matched.add(entry.displayName)
            }
        }
        val computed = if (matched.isEmpty()) emptyList() else ArrayList(matched)
        completionNamesByPlayer[uuid] = computed
        return computed
    }

    /**
     * owner 补全：在 TreeSet 上按前缀取有序子集，命中前缀区间结束即停，
     * 不再遍历全部名字。
     */
    private fun completeOwnerNames(prefix: String): List<String> {
        synchronized(completionOwnerOrder) {
            if (completionOwnerOrder.isEmpty()) {
                return emptyList()
            }
            val tail = if (prefix.isEmpty()) completionOwnerOrder else completionOwnerOrder.tailSet(prefix, true)
            val result = ArrayList<String>(minOf(COMPLETION_LIMIT, tail.size))
            for (value in tail) {
                if (!value.startsWith(prefix, ignoreCase = true)) break
                result += value
                if (result.size >= COMPLETION_LIMIT) break
            }
            return result
        }
    }

    private fun complete(values: List<String>, prefix: String): List<String> {
        if (values.isEmpty()) {
            return emptyList()
        }
        val result = ArrayList<String>(minOf(COMPLETION_LIMIT, values.size))
        for (value in values) {
            if (value.startsWith(prefix, ignoreCase = true)) {
                result += value
                if (result.size >= COMPLETION_LIMIT) break
            }
        }
        return result
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
        commandOverrideRegistered = true
    }

    private fun registerBridgeEvents() {
        if (bridgeEventsRegistered) {
            return
        }
        val manager = Bukkit.getPluginManager()
        manager.registerEvent(
            PlayerJoinEvent::class.java,
            bridgeListener,
            BukkitEventPriority.NORMAL,
            EventExecutor { _, event -> onJoin(event as PlayerJoinEvent) },
            plugin,
            false
        )
        manager.registerEvent(
            PlayerQuitEvent::class.java,
            bridgeListener,
            BukkitEventPriority.NORMAL,
            EventExecutor { _, event -> onQuit(event as PlayerQuitEvent) },
            plugin,
            false
        )
        manager.registerEvent(
            TabCompleteEvent::class.java,
            bridgeListener,
            BukkitEventPriority.NORMAL,
            EventExecutor { _, event -> onTabComplete(event as TabCompleteEvent) },
            plugin,
            false
        )
        bridgeEventsRegistered = true
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

    private fun ensureTeleportWaitListenerRegistered() {
        if (teleportWaitListenerRegistered) return
        if (config.teleportWait.cancelOnMove) {
            Bukkit.getPluginManager().registerEvent(
                PlayerMoveEvent::class.java,
                teleportWaitListener,
                BukkitEventPriority.MONITOR,
                EventExecutor { _, event -> handleMove(event as PlayerMoveEvent) },
                plugin,
                false
            )
        }
        if (config.teleportWait.cancelOnDamage) {
            Bukkit.getPluginManager().registerEvent(
                EntityDamageEvent::class.java,
                teleportWaitListener,
                BukkitEventPriority.MONITOR,
                EventExecutor { _, event -> handleDamage(event as EntityDamageEvent) },
                plugin,
                false
            )
        }
        teleportWaitListenerRegistered = true
    }

    private fun unregisterTeleportWaitListenerIfEmpty() {
        if (!teleportWaitListenerRegistered) return
        if (waitingTeleports.isNotEmpty()) return
        // 延迟注销：短时间内连续传送可复用已注册监听器，避免反复注册/注销
        BridgeScheduler.runGlobal(100L) {
            if (waitingTeleports.isEmpty() && teleportWaitListenerRegistered) {
                HandlerList.unregisterAll(teleportWaitListener)
                teleportWaitListenerRegistered = false
            }
        }
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
                // 刚创建时存档文件通常还没落盘，传送点由后续 confirmCreated 补齐。
                enqueueWrite(snapshot)
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
                enqueueDelete(name)
            }
            "ResidenceRenameEvent" -> {
                val oldName = event.invokeNoArg("getOldResidenceName")?.toString() ?: return
                val newName = event.invokeNoArg("getNewResidenceName")?.toString() ?: return
                val local = ResidenceHook.snapshotFromResidence(event.invokeNoArg("getResidence"), newName)
                    ?: ResidenceHook.toSnapshot(newName)
                    ?: return
                if (key(oldName) != local.nameKey) {
                    markLocalDeleted(oldName)
                }
                localDeleteTombstones.remove(local.nameKey)
                addCompletion(local.name, local.ownerUuid, local.ownerName)
                runAsync {
                    try {
                        database.replaceRenamed(oldName, ResidenceHook.withFileTeleportLocation(local))
                    } catch (t: Throwable) {
                        plugin.logger.warning("Residence rename sync failed: ${t.message}")
                    }
                }
            }
            "ResidenceOwnerChangeEvent" -> {
                val local = ResidenceHook.snapshotFromResidence(event.invokeNoArg("getResidence")) ?: return
                localDeleteTombstones.remove(local.nameKey)
                addCompletion(local.name, local.ownerUuid, local.ownerName)
                runAsync { enqueueWrite(ResidenceHook.withFileTeleportLocation(local)) }
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
                plugin.logger.log(java.util.logging.Level.WARNING, "Player task failed", t)
            }
        }
    }

    private fun Player.sendBridgeMessage(key: String, placeholders: Map<String, Any?> = emptyMap()) {
        runPlayer(this) { messages.send(this, key, placeholders) }
    }

    private fun Player.canListOthers(): Boolean {
        return isOp || hasPermission(config.list.othersPermission) || hasPermission("residencebridge.admin")
    }

    private fun formatMax(player: Player, max: Int): String =
        if (max == Int.MAX_VALUE) messages.text(player, "common.unlimited") else max.toString()

    /**
     * 刻意不使用 data class：这个对象靠身份（引用）区分「哪一次等待」，
     * 会被 ConcurrentHashMap.remove(key, value) 做原子比对。
     * data class 生成的 equals 会把可变的 tasks 列表算进去，比较结果会随任务增删漂移。
     */
    private class WaitingTeleport(
        val entry: ResidenceIndexEntry,
        val worldName: String?,
        val x: Int,
        val y: Int,
        val z: Int
    ) {
        private val tasks = mutableListOf<BridgeTask>()

        fun addTask(task: BridgeTask) {
            tasks += task
        }

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
            val nativeSuggestions = runCatching { original.tabComplete(sender, alias, args) }.getOrElse { mutableListOf() }
            val bridgeSuggestions = tabCompleteFromCommandMap(sender, args) ?: return nativeSuggestions
            return combineTabCompletions(args[0], nativeSuggestions, bridgeSuggestions)
        }
    }

    private fun parseResidenceCommand(message: String): ParsedResidenceCommand? {
        val rawCommand = message.removePrefix("/").trim()
        val parts = splitArguments(rawCommand)
        if (parts.size < 2) {
            return null
        }
        val root = parts[0].lowercase(Locale.ROOT)
        if (root !in residenceRoots) {
            return null
        }
        return ParsedResidenceCommand(root, parts[1].lowercase(Locale.ROOT), parts.subList(2, parts.size), rawCommand)
    }

    private fun parseTabBuffer(buffer: String): ParsedTabCommand? {
        val rawCommand = buffer.removePrefix("/")
        val trailingSpace = rawCommand.lastOrNull()?.isWhitespace() == true
        val parts = splitArguments(rawCommand)
        if (parts.size < 2) {
            return null
        }
        val root = parts[0].lowercase(Locale.ROOT)
        if (root !in residenceRoots) {
            return null
        }
        val subCommand = parts[1].lowercase(Locale.ROOT)
        val argCount = parts.size - 2
        val argIndex = if (trailingSpace) argCount else (argCount - 1).coerceAtLeast(0)
        val currentArg = if (trailingSpace || argCount == 0) "" else parts[parts.size - 1]
        return ParsedTabCommand(subCommand, argIndex, currentArg)
    }
}

/**
 * 快照内容指纹，用于增量同步时跳过未变化的记录。
 *
 * 必须覆盖所有会写入数据库的字段。注意 [ResidenceSnapshot.name] 也要参与：
 * nameKey 是小写化后的键，`Home` 改名为 `home` 时 nameKey 不变，
 * 若指纹不含原始 name，display_name 的变化就永远同步不出去。
 */
internal fun fingerprint(snapshot: ResidenceSnapshot): Long {
    var result = 1L
    fun mix(value: Long) {
        result = result * 31 + value
    }
    mix(snapshot.name.hashCode().toLong())
    mix(snapshot.ownerUuid?.mostSignificantBits ?: 0L)
    mix(snapshot.ownerUuid?.leastSignificantBits ?: 0L)
    mix(snapshot.ownerName?.hashCode()?.toLong() ?: 0L)
    mix(snapshot.worldName?.hashCode()?.toLong() ?: 0L)
    val teleport = snapshot.teleportLocation
    if (teleport != null) {
        mix(teleport.worldName.hashCode().toLong())
        mix(teleport.x.toRawBits())
        mix(teleport.y.toRawBits())
        mix(teleport.z.toRawBits())
        mix(teleport.yaw.toRawBits().toLong())
        mix(teleport.pitch.toRawBits().toLong())
    } else {
        mix(-7046029254386353131L) // 0x9E3779B97F4A7C15 的带符号位模式
    }
    return result
}

internal val residenceRoots = setOf("res", "residence", "resadmin", "residenceadmin")

/**
 * 只看第一个 token 是否为 res 系列根命令。每条玩家命令都会走这里，
 * 所以不做分词、不建正则、不产生中间字符串。
 */
internal fun isResidenceCommandMessage(message: String): Boolean {
    var start = 0
    if (start < message.length && message[start] == '/') start++
    while (start < message.length && message[start].isWhitespace()) start++
    var end = start
    while (end < message.length && !message[end].isWhitespace()) end++
    if (end == start) return false
    // 必须还有第二个 token（子命令），否则我们不接管
    var next = end
    while (next < message.length && message[next].isWhitespace()) next++
    if (next >= message.length) return false
    return residenceRoots.any { root ->
        root.length == end - start && message.regionMatches(start, root, 0, root.length, ignoreCase = true)
    }
}

/**
 * 按空白切分，跳过连续空白。等价于 `split(Regex("\\s+")).filter { it.isNotEmpty() }`，
 * 但不编译正则、不产生中间集合。
 */
internal fun splitArguments(raw: String): List<String> {
    val parts = ArrayList<String>(4)
    var index = 0
    while (index < raw.length) {
        while (index < raw.length && raw[index].isWhitespace()) index++
        if (index >= raw.length) break
        val start = index
        while (index < raw.length && !raw[index].isWhitespace()) index++
        parts += raw.substring(start, index)
    }
    return parts
}

private val commandsWithNativeResidenceCompletions = setOf(
    "remove"
)

internal fun combineTabCompletions(
    subCommand: String,
    nativeSuggestions: Iterable<String>,
    bridgeSuggestions: Iterable<String>
): MutableList<String> {
    val groups = if (subCommand.lowercase(Locale.ROOT) in commandsWithNativeResidenceCompletions) {
        arrayOf(nativeSuggestions, bridgeSuggestions)
    } else {
        arrayOf(bridgeSuggestions)
    }
    val completionsByKey = linkedMapOf<String, String>()
    groups.forEach { completions ->
        completions.forEach { completion ->
            completionsByKey.putIfAbsent(completion.lowercase(Locale.ROOT), completion)
        }
    }
    return completionsByKey.values.sortedWith(String.CASE_INSENSITIVE_ORDER).toMutableList()
}
