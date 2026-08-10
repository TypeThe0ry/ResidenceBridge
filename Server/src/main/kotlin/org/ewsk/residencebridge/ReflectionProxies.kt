package org.ewsk.residencebridge

import net.momirealms.sparrow.reflection.SReflection
import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter
import net.momirealms.sparrow.reflection.proxy.annotation.MethodInvoker
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy
import net.momirealms.sparrow.reflection.proxy.annotation.Type
import org.bukkit.entity.Player

internal fun <T> proxySafe(block: () -> T?): T? = try {
    block()
} catch (_: Throwable) {
    null
}

internal fun <T> createProxy(clazz: Class<T>): T? {
    initReflection()
    return runCatching { ASMProxyFactory.create(clazz) }.getOrNull()
}

@Volatile
private var reflectionInitialized = false

private object ReflectionLock

private fun initReflection() {
    if (reflectionInitialized) return
    synchronized(ReflectionLock) {
        if (reflectionInitialized) return
        runCatching { SReflection.setAsmClassPrefix("ResidenceBridge") }
        reflectionInitialized = true
    }
}

@ReflectionProxy(name = ["com.bekvon.bukkit.residence.Residence"], optional = true)
interface ResidencePluginProxy {
    @MethodInvoker(name = ["getInstance"], isStatic = true, optional = true)
    fun getInstance(): Any?

    @MethodInvoker(name = ["getResidenceManager"], optional = true)
    fun getResidenceManager(residence: Any?): Any?
}

@ReflectionProxy(
    name = [
        "com.bekvon.bukkit.residence.protection.ResidenceManager",
        "com.bekvon.bukkit.residence.ResidenceManager"
    ],
    optional = true
)
interface ResidenceManagerProxy {
    @MethodInvoker(name = ["getByName"], optional = true)
    fun getByName(manager: Any?, name: String): Any?

    @MethodInvoker(name = ["getResidences"], optional = true)
    fun getResidences(manager: Any?): Any?
}

@ReflectionProxy(name = ["com.bekvon.bukkit.residence.protection.ClaimedResidence"], optional = true)
interface ClaimedResidenceProxy {
    @MethodInvoker(name = ["getByName"], isStatic = true, optional = true)
    fun getByName(name: String): Any?

    @MethodInvoker(name = ["getName"], optional = true)
    fun getName(residence: Any?): String?

    @MethodInvoker(name = ["getOwner"], optional = true)
    fun getOwner(residence: Any?): String?

    @MethodInvoker(name = ["getOwnerUUID"], optional = true)
    fun getOwnerUUID(residence: Any?): String?

    @MethodInvoker(name = ["getWorldName"], optional = true)
    fun getWorldName(residence: Any?): String?

    @MethodInvoker(name = ["getPermissions"], optional = true)
    fun getPermissions(residence: Any?): Any?

    @MethodInvoker(name = ["getTeleportLocation"], optional = true)
    fun getTeleportLocation(residence: Any?, player: Player): Any?

    @MethodInvoker(name = ["getTeleportLocation"], optional = true)
    fun getTeleportLocation(residence: Any?, player: Player, force: Boolean): Any?
}

@ReflectionProxy(name = ["com.bekvon.bukkit.residence.containers.Flags"], optional = true)
interface FlagsProxy {
    @FieldGetter(name = ["tp"], isStatic = true, optional = true)
    fun tp(): Any?

    @FieldGetter(name = ["move"], isStatic = true, optional = true)
    fun move(): Any?
}

@ReflectionProxy(name = ["com.bekvon.bukkit.residence.protection.FlagPermissions"], optional = true)
interface FlagPermissionsProxy {
    @MethodInvoker(name = ["playerHas"], optional = true)
    fun playerHasFlag(
        permissions: Any?,
        player: Player,
        @Type(name = ["com.bekvon.bukkit.residence.containers.Flags"]) flag: Any?,
        def: Boolean
    ): Boolean

    @MethodInvoker(name = ["playerHas"], optional = true)
    fun playerHasCombo(
        permissions: Any?,
        player: Player,
        @Type(name = ["com.bekvon.bukkit.residence.containers.Flags"]) flag: Any?,
        @Type(name = ["com.bekvon.bukkit.residence.protection.FlagPermissions\$FlagCombo"]) combo: Any?
    ): Boolean
}

@ReflectionProxy(name = ["com.bekvon.bukkit.residence.protection.FlagPermissions\$FlagCombo"], optional = true)
interface FlagComboProxy {
    @FieldGetter(name = ["TrueOrNone"], isStatic = true, optional = true)
    fun trueOrNone(): Any?
}
