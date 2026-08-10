//by TypeThe0ry
package org.ewsk.residencebridge

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ResidenceHook {

    // ===== 反射缓存 =====
    private val classCache = ConcurrentHashMap<String, Class<*>?>()
    private val methodCache = ConcurrentHashMap<String, Method?>()
    private val fieldCache = ConcurrentHashMap<String, Field?>()
    private val flagCache = ConcurrentHashMap<String, Any?>()
    private val flagComboCache = ConcurrentHashMap<String, Any?>()

    // ===== sparrow-reflection Proxy =====
    private val residencePluginProxy by lazy { createProxy(ResidencePluginProxy::class.java) }
    private val managerProxy by lazy { createProxy(ResidenceManagerProxy::class.java) }
    private val claimedResidenceProxy by lazy { createProxy(ClaimedResidenceProxy::class.java) }
    private val flagsProxy by lazy { createProxy(FlagsProxy::class.java) }
    private val permissionsProxy by lazy { createProxy(FlagPermissionsProxy::class.java) }
    private val flagComboProxy by lazy { createProxy(FlagComboProxy::class.java) }

    private val residenceInstance: Any? by lazy { resolveResidenceInstance() }
    private val residenceManager: Any? by lazy {
        proxySafe { residencePluginProxy?.getResidenceManager(residenceInstance) }
            ?: residenceInstance?.value("getResidenceManager", "rmanager", "residenceManager")
    }
    private val flagsTp by lazy { proxySafe { flagsProxy?.tp() } ?: residenceFlag("tp") }
    private val flagsMove by lazy { proxySafe { flagsProxy?.move() } ?: residenceFlag("move") }
    private val flagComboTrueOrNone by lazy { proxySafe { flagComboProxy?.trueOrNone() } ?: residenceFlagCombo("TrueOrNone") }

    // ===== 文件快照缓存（按文件粒度：file -> (mtime, snapshots)） =====
    @Volatile
    private var fileSnapshotsCache: List<ResidenceSnapshot> = emptyList()
    @Volatile
    private var fileSnapshotsByNameKey: Map<String, ResidenceSnapshot> = emptyMap()
    @Volatile
    private var fileSnapshotsLastCheck: Long = 0L
    private val fileSnapshotEntries = ConcurrentHashMap<String, FileEntry>()
    private const val FILE_SNAPSHOT_CHECK_INTERVAL_MS = 5000L

    private data class FileEntry(val mtime: Long, val snapshots: List<ResidenceSnapshot>)

    fun exists(name: String): Boolean = getResidence(name) != null || fileSnapshot(name) != null

    fun isLoaded(name: String): Boolean = getResidence(name) != null

    fun getOwnerName(name: String): String? = getResidence(name)?.ownerName()

    fun getOwnerUuid(name: String): UUID? = getResidence(name)?.ownerUuid()

    fun getWorldName(name: String): String? = getResidence(name)?.worldName()

    fun teleport(player: Player, name: String): Boolean {
        val location = teleportLocation(player, name) ?: return false
        return player.teleport(location)
    }

    fun teleportLocation(player: Player, name: String): Location? {
        val residence = getResidence(name) ?: return null
        proxySafe { claimedResidenceProxy?.getTeleportLocation(residence, player, true) as? Location }?.let { return it }
        proxySafe { claimedResidenceProxy?.getTeleportLocation(residence, player) as? Location }?.let { return it }
        return (residence.invoke("getTeleportLocation", player, true) as? Location)
            ?: (residence.invoke("getTeleportLocation", player) as? Location)
    }

    fun canTeleport(player: Player, name: String): Boolean? {
        val residence = getResidence(name) ?: return null
        val permissions = proxySafe { claimedResidenceProxy?.getPermissions(residence) }
            ?: residence.invokeNoArg("getPermissions") ?: return null
        val trueOrNone = flagComboTrueOrNone
        val tp = flagsTp ?: return permissions.invoke("playerHas", player, "tp", false) as? Boolean
        val move = flagsMove
        val hasTp = if (trueOrNone != null) {
            proxySafe { permissionsProxy?.playerHasCombo(permissions, player, tp, trueOrNone) }
                ?: (permissions.invoke("playerHas", player, tp, trueOrNone) as? Boolean)
        } else {
            proxySafe { permissionsProxy?.playerHasFlag(permissions, player, tp, false) }
                ?: (permissions.invoke("playerHas", player, tp, false) as? Boolean)
        } ?: return null
        val hasMove = move?.let {
            if (trueOrNone != null) {
                proxySafe { permissionsProxy?.playerHasCombo(permissions, player, it, trueOrNone) }
                    ?: (permissions.invoke("playerHas", player, it, trueOrNone) as? Boolean)
            } else {
                proxySafe { permissionsProxy?.playerHasFlag(permissions, player, it, false) }
                    ?: (permissions.invoke("playerHas", player, it, false) as? Boolean)
            }
        } ?: true
        return hasTp && hasMove
    }

    fun allSnapshots(): List<ResidenceSnapshot> {
        return try {
            // 批量路径：直接从内存 map 取对象，避免 name -> getResidence 二次查找
            val snapshots = residenceValues().mapNotNull { residence ->
                val snapshot = snapshotFromResidence(residence) ?: return@mapNotNull null
                val fileSnapshot = fileSnapshot(snapshot.nameKey)
                snapshot.copy(teleportLocation = fileSnapshot?.teleportLocation ?: snapshot.teleportLocation)
            }
            snapshots.ifEmpty { fileSnapshots() }
        } catch (_: Throwable) {
            fileSnapshots()
        }
    }

    fun toSnapshot(name: String): ResidenceSnapshot? {
        val residence = getResidence(name) ?: return fileSnapshot(name)
        val residenceName = residence.residenceName() ?: name
        val snapshot = snapshotFromResidence(residence, residenceName) ?: return fileSnapshot(name)
        val fileSnapshot = fileSnapshot(residenceName)
        return snapshot.copy(teleportLocation = fileSnapshot?.teleportLocation ?: snapshot.teleportLocation)
    }

    fun snapshotFromResidence(residence: Any?, nameHint: String? = null): ResidenceSnapshot? {
        if (residence == null) {
            return null
        }
        val residenceName = residence.residenceName() ?: nameHint ?: return null
        return ResidenceSnapshot(
            name = residenceName,
            ownerUuid = residence.ownerUuid(),
            ownerName = residence.ownerName(),
            worldName = residence.worldName()
        )
    }

    fun diagnostics(): List<String> {
        val manager = residenceManager
        val values = residenceValues()
        val names = residenceNames()
        val fileSnaps = fileSnapshots()
        val allSnaps = allSnapshots()
        return listOf(
            "Residence plugin: ${Bukkit.getPluginManager().getPlugin("Residence")?.description?.fullName ?: "not found"}",
            "Residence instance: ${residenceInstance?.javaClass?.name ?: "null"}",
            "Residence manager: ${manager?.javaClass?.name ?: "null"}",
            "Residence names: ${names.size} ${names.joinToString()}",
            "Residence values: ${values.size}",
            "Residence file snapshots: ${fileSnaps.size} ${fileSnaps.joinToString { it.name }}",
            "Snapshots: ${allSnaps.size} ${allSnaps.joinToString { it.name }}"
        )
    }

    private fun fileSnapshots(): List<ResidenceSnapshot> {
        val now = System.currentTimeMillis()
        if (now - fileSnapshotsLastCheck < FILE_SNAPSHOT_CHECK_INTERVAL_MS) {
            return fileSnapshotsCache
        }
        fileSnapshotsLastCheck = now
        val residencePlugin = Bukkit.getPluginManager().getPlugin("Residence") ?: run {
            clearFileSnapshotsCache()
            return emptyList()
        }
        val worldsFolder = File(residencePlugin.dataFolder, "Save/Worlds")
        if (!worldsFolder.isDirectory) {
            clearFileSnapshotsCache()
            return emptyList()
        }
        val files = worldsFolder.listFiles { file -> file.isFile && file.extension.equals("yml", ignoreCase = true) }
            ?: emptyArray()
        var changed = false
        val knownNames = HashSet<String>(files.size)
        files.forEach { file ->
            val fileName = file.name
            knownNames.add(fileName)
            val mtime = file.lastModified()
            val cached = fileSnapshotEntries[fileName]
            if (cached == null || cached.mtime != mtime) {
                fileSnapshotEntries[fileName] = FileEntry(mtime, snapshotsFromFile(file))
                changed = true
            }
        }
        if (fileSnapshotEntries.keys.retainAll(knownNames)) {
            changed = true
        }
        if (changed) {
            rebuildFileSnapshotIndex()
        }
        return fileSnapshotsCache
    }

    private fun cachedFileSnapshots(): List<ResidenceSnapshot> = fileSnapshotsCache

    private fun rebuildFileSnapshotIndex() {
        val snapshots = fileSnapshotEntries.values.asSequence().flatMap { it.snapshots.asSequence() }.toList()
        fileSnapshotsCache = snapshots
        fileSnapshotsByNameKey = snapshots.associateBy { it.nameKey }
    }

    private fun clearFileSnapshotsCache() {
        fileSnapshotEntries.clear()
        fileSnapshotsCache = emptyList()
        fileSnapshotsByNameKey = emptyMap()
    }

    private fun snapshotsFromFile(file: File): List<ResidenceSnapshot> {
        val configuration = YamlConfiguration.loadConfiguration(file)
        val residences = configuration.getConfigurationSection("Residences") ?: return emptyList()
        val worldName = file.name.removePrefix("res_").removeSuffix(".yml")
        return residences.getKeys(false).map { name ->
            val path = "Residences.$name.Permissions"
            val area = configuration.getString("Residences.$name.Areas.main")
            val tpLocation = configuration.getString("Residences.$name.TPLoc")
            ResidenceSnapshot(
                name = name,
                ownerUuid = configuration.getString("$path.OwnerUUID")?.let { value ->
                    runCatching { UUID.fromString(value) }.getOrNull()
                },
                ownerName = configuration.getString("$path.OwnerLastKnownName"),
                worldName = worldName.takeIf { it.isNotBlank() },
                teleportLocation = tpLocation?.let { parseTeleportLocation(worldName, it) }
                    ?: area?.let { parseAreaCenter(worldName, it) }
            )
        }
    }

    private fun parseTeleportLocation(worldName: String, value: String): BridgeLocation? {
        val values = value.split(':').mapNotNull { it.toDoubleOrNull() }
        if (values.size < 3 || worldName.isBlank()) {
            return null
        }
        return BridgeLocation(
            worldName = worldName,
            x = values[0],
            y = values[1],
            z = values[2],
            yaw = values.getOrNull(3)?.toFloat() ?: 0f,
            pitch = values.getOrNull(4)?.toFloat() ?: 0f
        )
    }

    private fun parseAreaCenter(worldName: String, area: String): BridgeLocation? {
        val values = area.split(':').mapNotNull { it.toDoubleOrNull() }
        if (values.size != 6 || worldName.isBlank()) {
            return null
        }
        val minX = minOf(values[0], values[3])
        val maxX = maxOf(values[0], values[3])
        val maxY = maxOf(values[1], values[4])
        val minZ = minOf(values[2], values[5])
        val maxZ = maxOf(values[2], values[5])
        return BridgeLocation(
            worldName = worldName,
            x = (minX + maxX) / 2.0 + 0.5,
            y = maxY + 1.0,
            z = (minZ + maxZ) / 2.0 + 0.5
        )
    }

    private fun fileSnapshot(name: String): ResidenceSnapshot? {
        fileSnapshots()
        return fileSnapshotsByNameKey[key(name)]
    }

    private fun getResidence(name: String): Any? {
        val manager = residenceManager ?: return null
        proxySafe { managerProxy?.getByName(manager, name) }?.let { return it }
        manager.invokeString("getByName", name)?.let { return it }
        val map = residencesMap()
        map?.entries?.firstOrNull { (key, _) -> key?.toString()?.equals(name, ignoreCase = true) == true }?.value?.let { return it }
        return claimedResidenceByName(name)
    }

    private fun claimedResidenceByName(name: String): Any? {
        proxySafe { claimedResidenceProxy?.getByName(name) }?.let { return it }
        val clazz = loadClass("com.bekvon.bukkit.residence.protection.ClaimedResidence") ?: return null
        val method = methodCache.getOrPut("${clazz.name}#getByName(java.lang.String)") {
            runCatching { clazz.getMethod("getByName", String::class.java) }.getOrNull()
        } ?: return null
        return runCatching { method.invoke(null, name) }.getOrNull()
    }

    private fun residenceValues(): Collection<Any> {
        val map = residencesMap()
        if (map != null) {
            return map.values.filterNotNull()
        }
        val manager = residenceManager ?: return emptyList()
        val collection = manager.value("getResidences", "residences") as? Collection<*>
        return collection?.filterNotNull() ?: emptyList()
    }

    private fun residenceNames(): List<String> {
        val map = residencesMap()
        if (map != null && map.isNotEmpty()) {
            return map.keys.mapNotNull { it?.toString()?.takeIf { name -> name.isNotBlank() } }
        }
        val manager = residenceManager ?: return emptyList()
        val value = manager.invokeNoArg("getResidenceList") ?: return emptyList()
        return when (value) {
            is Array<*> -> value.mapNotNull { it?.toString()?.takeIf { name -> name.isNotBlank() } }
            is Iterable<*> -> value.mapNotNull { it?.toString()?.takeIf { name -> name.isNotBlank() } }
            else -> emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun residencesMap(): Map<Any?, Any?>? {
        val manager = residenceManager ?: return null
        proxySafe { managerProxy?.getResidences(manager) as? Map<Any?, Any?> }?.let { return it }
        return manager.value("getResidences", "residences") as? Map<Any?, Any?>
    }

    private fun resolveResidenceInstance(): Any? {
        proxySafe { residencePluginProxy?.getInstance() }?.let { return it }
        val plugin = Bukkit.getPluginManager().getPlugin("Residence")
        if (plugin != null && plugin.javaClass.name == "com.bekvon.bukkit.residence.Residence") {
            return plugin
        }
        val clazz = loadClass("com.bekvon.bukkit.residence.Residence") ?: return plugin
        return runCatching { clazz.getMethod("getInstance").invoke(null) }.getOrNull() ?: plugin
    }

    private fun loadClass(className: String): Class<*>? {
        return classCache.getOrPut(className) {
            runCatching { Class.forName(className) }.getOrNull()
                ?: Bukkit.getPluginManager().getPlugin("Residence")?.javaClass?.classLoader?.let { loader ->
                    runCatching { loader.loadClass(className) }.getOrNull()
                }
        }
    }

    private fun residenceFlag(name: String): Any? {
        return flagCache.getOrPut(name) {
            val clazz = loadClass("com.bekvon.bukkit.residence.containers.Flags") ?: return@getOrPut null
            runCatching { clazz.getField(name).get(null) }.getOrNull()
                ?: runCatching { clazz.getMethod("getFlag", String::class.java).invoke(null, name) }.getOrNull()
        }
    }

    private fun residenceFlagCombo(name: String): Any? {
        return flagComboCache.getOrPut(name) {
            val clazz = loadClass("com.bekvon.bukkit.residence.protection.FlagPermissions\$FlagCombo") ?: return@getOrPut null
            runCatching { clazz.getField(name).get(null) }.getOrNull()
                ?: runCatching { clazz.getMethod("valueOf", String::class.java).invoke(null, name) }.getOrNull()
        }
    }

    private fun Any.residenceName(): String? {
        proxySafe { claimedResidenceProxy?.getName(this) }?.takeIf { it.isNotBlank() }?.let { return it }
        return stringValue("getName", "name")
    }

    private fun Any.ownerName(): String? {
        proxySafe { claimedResidenceProxy?.getOwner(this) }?.takeIf { it.isNotBlank() }?.let { return it }
        return stringValue("getOwner", "owner")
    }

    private fun Any.ownerUuid(): UUID? {
        val value = proxySafe { claimedResidenceProxy?.getOwnerUUID(this) }
            ?: value("getOwnerUUID", "ownerUUID") ?: return null
        return when (value) {
            is UUID -> value
            else -> runCatching { UUID.fromString(value.toString()) }.getOrNull()
        }
    }

    private fun Any.worldName(): String? {
        val value = proxySafe { claimedResidenceProxy?.getWorldName(this) }
            ?: value("getWorldName", "worldName", "getWorld", "world") ?: return null
        return when (value) {
            is World -> value.name
            else -> value.toString().takeIf { it.isNotBlank() }
        }
    }

    private fun Any.stringValue(vararg names: String): String? {
        return value(*names)?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun Any.value(vararg names: String): Any? {
        names.forEach { name ->
            invokeNoArg(name)?.let { return it }
            field(name)?.let { field ->
                return runCatching {
                    field.isAccessible = true
                    field.get(this)
                }.getOrNull()
            }
        }
        return null
    }

    private fun Any.invokeNoArg(name: String): Any? {
        val method = method(name) ?: return null
        return runCatching {
            method.isAccessible = true
            method.invoke(this)
        }.getOrNull()
    }

    private fun Any.invokeString(name: String, argument: String): Any? {
        val method = method(name, String::class.java) ?: return null
        return runCatching {
            method.isAccessible = true
            method.invoke(this, argument)
        }.getOrNull()
    }

    private fun Any.invoke(name: String, vararg arguments: Any?): Any? {
        val parameterTypes = when {
            name == "tpToResidence" && arguments.size == 3 -> arrayOf(Player::class.java, Player::class.java, java.lang.Boolean.TYPE)
            name == "getTeleportLocation" && arguments.size == 1 && arguments[0] is Player -> arrayOf(Player::class.java)
            name == "getTeleportLocation" && arguments.size == 2 && arguments[0] is Player && arguments[1] is Boolean -> {
                arrayOf(Player::class.java, java.lang.Boolean.TYPE)
            }
            name == "playerHas" && arguments.size == 3 && arguments[0] is Player && arguments[2] is Boolean -> {
                arrayOf(Player::class.java, arguments[1]!!.javaClass, java.lang.Boolean.TYPE)
            }
            name == "playerHas" && arguments.size == 3 && arguments[0] is Player && arguments[1] is String && arguments[2] is Boolean -> {
                arrayOf(Player::class.java, String::class.java, java.lang.Boolean.TYPE)
            }
            else -> arguments.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        }
        val method = method(name, *parameterTypes) ?: return null
        return runCatching {
            method.isAccessible = true
            method.invoke(this, *arguments)
        }.getOrNull()
    }

    private fun Any.method(name: String, vararg parameterTypes: Class<*>): Method? {
        val key = methodKey(javaClass, name, parameterTypes)
        return methodCache.getOrPut(key) { resolveMethod(javaClass, name, parameterTypes) }
    }

    private fun resolveMethod(clazz: Class<*>, name: String, parameterTypes: Array<out Class<*>>): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            val method = runCatching { current!!.getDeclaredMethod(name, *parameterTypes) }.getOrNull()
            if (method != null) return method
            current = current.superclass
        }
        return runCatching { clazz.getMethod(name, *parameterTypes) }.getOrNull()
    }

    private fun methodKey(clazz: Class<*>, name: String, parameterTypes: Array<out Class<*>>): String {
        if (parameterTypes.isEmpty()) return "${clazz.name}#$name()"
        val sb = StringBuilder(clazz.name.length + name.length + parameterTypes.size * 30)
        sb.append(clazz.name).append('#').append(name).append('(')
        parameterTypes.forEachIndexed { i, type ->
            if (i > 0) sb.append(',')
            sb.append(type.name)
        }
        sb.append(')')
        return sb.toString()
    }

    private fun Any.field(name: String): Field? {
        val key = "${javaClass.name}#$name"
        return fieldCache.getOrPut(key) { resolveField(javaClass, name) }
    }

    private fun resolveField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            val declared = runCatching { current!!.getDeclaredField(name) }.getOrNull()
            if (declared != null) return declared
            val public = runCatching { current!!.getField(name) }.getOrNull()
            if (public != null) return public
            current = current.superclass
        }
        return null
    }

}
