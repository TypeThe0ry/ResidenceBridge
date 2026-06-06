package org.ewsk.residencebridge

import org.bukkit.entity.Player
import java.util.UUID

object ResidenceHook {

    fun exists(name: String): Boolean = getResidence(name) != null

    fun getOwnerName(name: String): String? = getResidence(name)?.stringValue("getOwner", "owner")

    fun getOwnerUuid(name: String): UUID? = getResidence(name)?.uuidValue("getOwnerUUID", "ownerUUID")

    fun getWorldName(name: String): String? {
        val residence = getResidence(name) ?: return null
        return residence.stringValue("getWorldName", "worldName") ?: residence.stringValue("getWorld", "world")
    }

    fun teleport(player: Player, name: String): Boolean {
        val residence = getResidence(name) ?: return false
        return try {
            val method = residence.javaClass.methods.firstOrNull {
                it.name == "tpToResidence" && it.parameterTypes.size == 3
            } ?: return false
            method.invoke(residence, player, player, true)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun allSnapshots(): List<ResidenceSnapshot> {
        return try {
            val values = residenceObjects()
            values.mapNotNull { toSnapshot(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun toSnapshot(name: String): ResidenceSnapshot? {
        val residence = getResidence(name) ?: return null
        return toSnapshot(residence)
    }

    private fun toSnapshot(residence: Any): ResidenceSnapshot? {
        val name = residence.stringValue("getName", "name") ?: return null
        return ResidenceSnapshot(
            name = name,
            ownerUuid = residence.uuidValue("getOwnerUUID", "ownerUUID"),
            ownerName = residence.stringValue("getOwner", "owner"),
            worldName = residence.stringValue("getWorldName", "worldName") ?: residence.stringValue("getWorld", "world")
        )
    }

    private fun getResidence(name: String): Any? {
        return try {
            val manager = residenceManager() ?: return null
            val method = manager.javaClass.methods.firstOrNull { it.name == "getByName" && it.parameterTypes.size == 1 }
                ?: return null
            method.invoke(manager, name)
        } catch (_: Throwable) {
            null
        }
    }

    private fun residenceManager(): Any? {
        val residence = try {
            val clazz = Class.forName("com.bekvon.bukkit.residence.Residence")
            clazz.getMethod("getInstance").invoke(null)
        } catch (_: Throwable) {
            return null
        } ?: return null
        return residence.value("getResidenceManager", "residenceManager")
    }

    private fun residenceObjects(): Collection<Any> {
        val manager = residenceManager() ?: return emptyList()
        return when (val residences = manager.value("getResidences", "residences")) {
            is Map<*, *> -> residences.values.filterNotNull()
            is Collection<*> -> residences.filterNotNull()
            else -> emptyList()
        }
    }

    private fun Any.stringValue(vararg names: String): String? = value(*names)?.toString()

    private fun Any.uuidValue(vararg names: String): UUID? {
        val value = value(*names) ?: return null
        return when (value) {
            is UUID -> value
            else -> runCatching { UUID.fromString(value.toString()) }.getOrNull()
        }
    }

    private fun Any.value(vararg names: String): Any? {
        names.forEach { name ->
            try {
                val method = javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                if (method != null) {
                    return method.invoke(this)
                }
            } catch (_: Throwable) {
            }
            try {
                val field = javaClass.getDeclaredField(name)
                field.isAccessible = true
                return field.get(this)
            } catch (_: Throwable) {
            }
        }
        return null
    }
}