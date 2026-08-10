package org.ewsk.residencebridge

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ResidencePlaceholderExpansion(
    private val config: BridgeConfig,
    private val database: BridgeDatabase
) : PlaceholderExpansion() {

    private val cache = ConcurrentHashMap<UUID, CachedPlaceholderData>()
    private val refreshing = ConcurrentHashMap.newKeySet<UUID>()

    override fun getIdentifier(): String = "reslink"

    override fun getAuthor(): String = "ResidenceBridge"

    override fun getVersion(): String = "1.2.4"

    override fun persist(): Boolean = true

    override fun canRegister(): Boolean = true

    override fun onPlaceholderRequest(player: Player?, params: String): String {
        if (player == null) {
            return ""
        }
        val data = dataFor(player)
        val normalized = params.lowercase(java.util.Locale.ROOT)
        if (normalized == "ressize") {
            return data.total.toString()
        }
        if (normalized.startsWith("reslist_")) {
            val index = normalized.removePrefix("reslist_").toIntOrNull() ?: return ""
            return data.names.getOrNull(index - 1) ?: ""
        }
        return ""
    }

    private fun dataFor(player: Player): CachedPlaceholderData {
        val now = System.currentTimeMillis()
        val uuid = player.uniqueId
        val cached = cache[uuid]
        if (cached != null) {
            if (cached.expireAt > now) {
                return cached
            }
            // 过期：返回旧值并异步刷新
            triggerRefresh(player)
            return cached
        }
        // 首次无缓存：返回空值并异步加载
        triggerRefresh(player)
        return EMPTY_DATA
    }

    private fun triggerRefresh(player: Player) {
        val uuid = player.uniqueId
        if (!refreshing.add(uuid)) return
        val playerName = player.name
        BridgeScheduler.runAsync {
            try {
                val list = database.listResidencesByOwner(uuid, playerName, 1, 256)
                val now = System.currentTimeMillis()
                cache[uuid] = CachedPlaceholderData(
                    total = list.total,
                    names = list.entries.map { it.displayName },
                    expireAt = now + config.placeholderCacheSeconds * 1000L
                )
            } catch (_: Throwable) {
            } finally {
                refreshing.remove(uuid)
            }
        }
    }

    private companion object {
        val EMPTY_DATA = CachedPlaceholderData(0, emptyList(), 0L)
    }

    private data class CachedPlaceholderData(
        val total: Int,
        val names: List<String>,
        val expireAt: Long
    )
}
