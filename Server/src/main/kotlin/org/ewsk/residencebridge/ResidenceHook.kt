package org.ewsk.residencebridge

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.UUID

object ResidenceHook {

    fun exists(name: String): Boolean = getResidence(name) != null

    fun getOwnerName(name: String): String? = getResidence(name)?.ownerName()

    fun getOwnerUuid(name: String): UUID? = getResidence(name)?.ownerUuid()

    fun getWorldName(name: String): String? = getResidence(name)?.worldName()

    fun teleport(player: Player, name: String): Boolean {
        val residence = getResidence(name) ?: return false
        return try {
            val method = residence.javaClass.allMethods().firstOrNull {
                it.name == "tpToResidence" && it.parameterTypes.size == 3
            } ?: return false
            method.isAccessible = true
            method.invoke(residence, player, player, true)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun allSnapshots(): List<ResidenceSnapshot> {
        return try {
            residenceCandidates().mapNotNull { toSnapshot(it.residence, it.nameHint) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun toSnapshot(name: String): ResidenceSnapshot? {
        return getResidence(name)?.let { toSnapshot(it, name) }
    }

    fun diagnostics(): List<String> {
        val lines = mutableListOf<String>()
        val plugin = Bukkit.getPluginManager().getPlugin("Residence")
        lines += "Residence plugin: ${plugin?.description?.fullName ?: "not found"}"
        val clazz = tryLoadResidenceClass()
        lines += "Residence class: ${clazz?.name ?: "not found"}"
        val instance = residenceInstance()
        lines += "Residence instance: ${instance?.javaClass?.name ?: "null"}"
        val manager = residenceManager()
        lines += "Residence manager: ${manager?.javaClass?.name ?: "null"}"
        if (manager != null) {
            lines += "Manager res-like methods: " + manager.javaClass.allMethods()
                .filter { it.parameterTypes.isEmpty() && it.name.lowercase().contains("res") }
                .take(12)
                .joinToString { "${it.name}:${it.returnType.simpleName}" }
            lines += "Manager res-like fields: " + manager.javaClass.allFields()
                .filter { it.name.lowercase().contains("res") }
                .take(12)
                .joinToString { "${it.name}:${it.type.simpleName}" }
        }
        val candidates = residenceCandidates()
        lines += "Residence candidates: ${candidates.size}"
        candidates.take(10).forEachIndexed { index, candidate ->
            lines += "#$index hint=${candidate.nameHint ?: "null"}, class=${candidate.residence.javaClass.name}, name=${candidate.residence.residenceName() ?: "null"}, owner=${candidate.residence.ownerName() ?: "null"}, world=${candidate.residence.worldName() ?: "null"}"
        }
        val snapshots = allSnapshots()
        lines += "Snapshots: ${snapshots.size} ${snapshots.joinToString { it.name }}"
        return lines
    }

    private fun toSnapshot(residence: Any, nameHint: String? = null): ResidenceSnapshot? {
        val name = residence.residenceName() ?: nameHint?.takeIf { it.isNotBlank() } ?: return null
        return ResidenceSnapshot(
            name = name,
            ownerUuid = residence.ownerUuid(),
            ownerName = residence.ownerName(),
            worldName = residence.worldName()
        )
    }

    private fun getResidence(name: String): Any? {
        val manager = residenceManager() ?: return null
        val preferredNames = listOf("getByName", "getResidence", "getResidenceByName", "getByResName")
        preferredNames.forEach { methodName ->
            manager.invokeStringMethod(methodName, name)?.let { return it }
        }
        return manager.javaClass.allMethods()
            .filter { it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java }
            .filter { it.returnType != Void.TYPE }
            .filter { method ->
                val normalized = method.name.lowercase()
                normalized.contains("res") || normalized.contains("name")
            }
            .firstNotNullOfOrNull { method ->
                runCatching {
                    method.isAccessible = true
                    method.invoke(manager, name)
                }.getOrNull()
            }
            ?: residenceCandidates().firstOrNull { candidate ->
                val candidateName = candidate.residence.residenceName() ?: candidate.nameHint
                candidateName != null && key(candidateName) == key(name)
            }?.residence
    }

    private fun residenceCandidates(): Collection<ResidenceCandidate> {
        val manager = residenceManager() ?: return emptyList()

        val preferred = manager.value("getResidences", "residences", "getResidenceList", "residenceList")
        val preferredCandidates = preferred.toResidenceCandidates()
        if (preferredCandidates.isNotEmpty()) {
            return preferredCandidates
        }

        manager.javaClass.allMethods()
            .filter { it.parameterTypes.isEmpty() && it.returnType != Void.TYPE }
            .filter { it.name.lowercase().contains("res") }
            .forEach { method ->
                val value = runCatching {
                    method.isAccessible = true
                    method.invoke(manager)
                }.getOrNull()
                val candidates = value.toResidenceCandidates()
                if (candidates.isNotEmpty()) {
                    return candidates
                }
            }

        manager.javaClass.allFields()
            .filter { it.name.lowercase().contains("res") }
            .forEach { field ->
                val value = runCatching {
                    field.isAccessible = true
                    field.get(manager)
                }.getOrNull()
                val candidates = value.toResidenceCandidates()
                if (candidates.isNotEmpty()) {
                    return candidates
                }
            }

        return emptyList()
    }

    private fun residenceManager(): Any? {
        val residence = residenceInstance() ?: return null
        return residence.value("getResidenceManager", "residenceManager", "getManager", "manager")
    }

    private fun residenceInstance(): Any? {
        return tryLoadResidenceClass()?.let { clazz ->
            runCatching { clazz.getMethod("getInstance").invoke(null) }.getOrNull()
        }
    }

    private fun tryLoadResidenceClass(): Class<*>? {
        val className = "com.bekvon.bukkit.residence.Residence"
        runCatching { Class.forName(className) }.getOrNull()?.let { return it }
        val plugin = Bukkit.getPluginManager().getPlugin("Residence") ?: return null
        return runCatching { plugin.javaClass.classLoader.loadClass(className) }.getOrNull()
    }

    private fun Any.residenceName(): String? {
        return stringValue("getName", "name", "getResidenceName", "residenceName", "getResName", "resName")
    }

    private fun Any.ownerName(): String? {
        return stringValue("getOwner", "owner", "getOwnerName", "ownerName")
    }

    private fun Any.ownerUuid(): UUID? {
        val value = value("getOwnerUUID", "ownerUUID", "getOwnerUuid", "ownerUuid") ?: return null
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
            fieldValue(name)?.let { return it }
        }
        return null
    }

    private fun Any.invokeStringMethod(name: String, argument: String): Any? {
        val method = javaClass.allMethods().firstOrNull { it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java }
            ?: return null
        return runCatching {
            method.isAccessible = true
            method.invoke(this, argument)
        }.getOrNull()
    }

    private fun Any.invokeNoArg(name: String): Any? {
        val method = javaClass.allMethods().firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            ?: return null
        return runCatching {
            method.isAccessible = true
            method.invoke(this)
        }.getOrNull()
    }

    private fun Any.fieldValue(name: String): Any? {
        val field = javaClass.allFields().firstOrNull { it.name == name } ?: return null
        return runCatching {
            field.isAccessible = true
            field.get(this)
        }.getOrNull()
    }

    private fun Any?.toResidenceCandidates(): Collection<ResidenceCandidate> {
        return when (this) {
            is Map<*, *> -> entries.mapNotNull { entry ->
                val residence = entry.value ?: return@mapNotNull null
                val nameHint = entry.key?.toString()?.takeIf { it.isNotBlank() }
                if (residence.looksLikeResidence() || nameHint != null) ResidenceCandidate(nameHint, residence) else null
            }
            is Collection<*> -> filterNotNull().filter { it.looksLikeResidence() }.map { ResidenceCandidate(null, it) }
            is Array<*> -> filterNotNull().filter { it.looksLikeResidence() }.map { ResidenceCandidate(null, it) }
            else -> emptyList()
        }
    }

    private data class ResidenceCandidate(val nameHint: String?, val residence: Any)

    private fun Any.looksLikeResidence(): Boolean {
        val className = javaClass.name.lowercase()
        return className.contains("residence") || residenceName() != null
    }

    private fun Class<*>.allMethods(): List<Method> {
        val result = linkedMapOf<String, Method>()
        var current: Class<*>? = this
        while (current != null) {
            current.declaredMethods.forEach { result.putIfAbsent(methodKey(it), it) }
            current.methods.forEach { result.putIfAbsent(methodKey(it), it) }
            current = current.superclass
        }
        return result.values.toList()
    }

    private fun Class<*>.allFields(): List<Field> {
        val result = linkedMapOf<String, Field>()
        var current: Class<*>? = this
        while (current != null) {
            current.declaredFields.forEach { result.putIfAbsent(it.name, it) }
            current.fields.forEach { result.putIfAbsent(it.name, it) }
            current = current.superclass
        }
        return result.values.toList()
    }

    private fun methodKey(method: Method): String {
        return method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
    }
}