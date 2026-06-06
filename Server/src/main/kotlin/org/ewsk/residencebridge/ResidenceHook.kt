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

object ResidenceHook {

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
        return (residence.invoke("getTeleportLocation", player, true) as? Location)
            ?: (residence.invoke("getTeleportLocation", player) as? Location)
    }

    fun canTeleport(player: Player, name: String): Boolean? {
        val residence = getResidence(name) ?: return null
        val permissions = residence.invokeNoArg("getPermissions") ?: return null
        val trueOrNone = residenceFlagCombo("TrueOrNone")
        val tp = residenceFlag("tp") ?: return permissions.invoke("playerHas", player, "tp", false) as? Boolean
        val move = residenceFlag("move")
        val hasTp = if (trueOrNone != null) {
            permissions.invoke("playerHas", player, tp, trueOrNone) as? Boolean
        } else {
            permissions.invoke("playerHas", player, tp, false) as? Boolean
        } ?: return null
        val hasMove = move?.let {
            if (trueOrNone != null) {
                permissions.invoke("playerHas", player, it, trueOrNone) as? Boolean
            } else {
                permissions.invoke("playerHas", player, it, false) as? Boolean
            }
        } ?: true
        return hasTp && hasMove
    }

    fun allSnapshots(): List<ResidenceSnapshot> {
        return try {
            val names = residenceNames()
            val snapshots = if (names.isNotEmpty()) {
                names.mapNotNull { toSnapshot(it) }
            } else {
                residenceValues().mapNotNull { residence ->
                    val residenceName = residence.residenceName() ?: return@mapNotNull null
                    toSnapshot(residenceName)
                }
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
        val manager = residenceManager()
        val values = residenceValues()
        val names = residenceNames()
        val fileSnapshots = fileSnapshots()
        return listOf(
            "Residence plugin: ${Bukkit.getPluginManager().getPlugin("Residence")?.description?.fullName ?: "not found"}",
            "Residence instance: ${residenceInstance()?.javaClass?.name ?: "null"}",
            "Residence manager: ${manager?.javaClass?.name ?: "null"}",
            "Residence names: ${names.size} ${names.joinToString()}",
            "Residence values: ${values.size}",
            "Residence file snapshots: ${fileSnapshots.size} ${fileSnapshots.joinToString { it.name }}",
            "Snapshots: ${allSnapshots().size} ${allSnapshots().joinToString { it.name }}"
        )
    }

    private fun fileSnapshots(): List<ResidenceSnapshot> {
        val residencePlugin = Bukkit.getPluginManager().getPlugin("Residence") ?: return emptyList()
        val worldsFolder = File(residencePlugin.dataFolder, "Save/Worlds")
        if (!worldsFolder.isDirectory) {
            return emptyList()
        }
        return worldsFolder.listFiles { file -> file.isFile && file.extension.equals("yml", ignoreCase = true) }
            ?.flatMap { file -> snapshotsFromFile(file) }
            ?: emptyList()
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
        val nameKey = key(name)
        return fileSnapshots().firstOrNull { it.nameKey == nameKey }
    }

    private fun getResidence(name: String): Any? {
        val manager = residenceManager() ?: return null
        manager.invokeString("getByName", name)?.let { return it }
        val map = residencesMap()
        map?.entries?.firstOrNull { (key, _) -> key?.toString()?.equals(name, ignoreCase = true) == true }?.value?.let { return it }
        return claimedResidenceByName(name)
    }

    private fun claimedResidenceByName(name: String): Any? {
        val className = "com.bekvon.bukkit.residence.protection.ClaimedResidence"
        val clazz = runCatching { Class.forName(className) }.getOrNull()
            ?: Bukkit.getPluginManager().getPlugin("Residence")?.javaClass?.classLoader?.let { loader ->
                runCatching { loader.loadClass(className) }.getOrNull()
            }
            ?: return null
        return runCatching { clazz.getMethod("getByName", String::class.java).invoke(null, name) }.getOrNull()
    }

    private fun residenceValues(): Collection<Any> {
        val map = residencesMap()
        if (map != null) {
            return map.values.filterNotNull()
        }
        val manager = residenceManager() ?: return emptyList()
        val collection = manager.value("getResidences", "residences") as? Collection<*>
        return collection?.filterNotNull() ?: emptyList()
    }

    private fun residenceNames(): List<String> {
        val map = residencesMap()
        if (map != null && map.isNotEmpty()) {
            return map.keys.mapNotNull { it?.toString()?.takeIf { name -> name.isNotBlank() } }
        }
        val manager = residenceManager() ?: return emptyList()
        val value = manager.invokeNoArg("getResidenceList") ?: return emptyList()
        return when (value) {
            is Array<*> -> value.mapNotNull { it?.toString()?.takeIf { name -> name.isNotBlank() } }
            is Iterable<*> -> value.mapNotNull { it?.toString()?.takeIf { name -> name.isNotBlank() } }
            else -> emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun residencesMap(): Map<Any?, Any?>? {
        val manager = residenceManager() ?: return null
        return manager.value("getResidences", "residences") as? Map<Any?, Any?>
    }

    private fun residenceManager(): Any? {
        val residence = residenceInstance() ?: return null
        return residence.value("getResidenceManager", "rmanager", "residenceManager")
    }

    private fun residenceInstance(): Any? {
        val plugin = Bukkit.getPluginManager().getPlugin("Residence")
        if (plugin != null && plugin.javaClass.name == "com.bekvon.bukkit.residence.Residence") {
            return plugin
        }
        val clazz = residenceClass() ?: return plugin
        return runCatching { clazz.getMethod("getInstance").invoke(null) }.getOrNull() ?: plugin
    }

    private fun residenceClass(): Class<*>? {
        val className = "com.bekvon.bukkit.residence.Residence"
        runCatching { Class.forName(className) }.getOrNull()?.let { return it }
        val plugin = Bukkit.getPluginManager().getPlugin("Residence") ?: return null
        return runCatching { plugin.javaClass.classLoader.loadClass(className) }.getOrNull()
    }

    private fun residenceFlag(name: String): Any? {
        val className = "com.bekvon.bukkit.residence.containers.Flags"
        val clazz = runCatching { Class.forName(className) }.getOrNull()
            ?: Bukkit.getPluginManager().getPlugin("Residence")?.javaClass?.classLoader?.let { loader ->
                runCatching { loader.loadClass(className) }.getOrNull()
            }
            ?: return null
        return runCatching { clazz.getField(name).get(null) }.getOrNull()
            ?: runCatching { clazz.getMethod("getFlag", String::class.java).invoke(null, name) }.getOrNull()
    }

    private fun residenceFlagCombo(name: String): Any? {
        val className = "com.bekvon.bukkit.residence.protection.FlagPermissions\$FlagCombo"
        val clazz = runCatching { Class.forName(className) }.getOrNull()
            ?: Bukkit.getPluginManager().getPlugin("Residence")?.javaClass?.classLoader?.let { loader ->
                runCatching { loader.loadClass(className) }.getOrNull()
            }
            ?: return null
        return runCatching { clazz.getField(name).get(null) }.getOrNull()
            ?: runCatching { clazz.getMethod("valueOf", String::class.java).invoke(null, name) }.getOrNull()
    }

    private fun Any.residenceName(): String? = stringValue("getName", "name")

    private fun Any.ownerName(): String? = stringValue("getOwner", "owner")

    private fun Any.ownerUuid(): UUID? {
        val value = value("getOwnerUUID", "ownerUUID") ?: return null
        return when (value) {
            is UUID -> value
            else -> runCatching { UUID.fromString(value.toString()) }.getOrNull()
        }
    }

    private fun Any.worldName(): String? {
        val value = value("getWorldName", "worldName", "getWorld", "world") ?: return null
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
        var current: Class<*>? = javaClass
        while (current != null) {
            runCatching { return current.getDeclaredMethod(name, *parameterTypes) }
            current = current.superclass
        }
        return runCatching { javaClass.getMethod(name, *parameterTypes) }.getOrNull()
    }

    private fun Any.field(name: String): Field? {
        var current: Class<*>? = javaClass
        while (current != null) {
            runCatching { return current.getDeclaredField(name) }
            runCatching { return current.getField(name) }
            current = current.superclass
        }
        return null
    }

}