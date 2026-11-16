package org.ewsk.residencebridge

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ResidencePlaceholderExpansion(
    private val config: BridgeConfig,
    private val database: BridgeDatabase
) : PlaceholderExpansion() {

    private val cache = ConcurrentHashMap<UUID, CachedPlaceholderData>()
    private val refreshing = ConcurrentHashMap.newKeySet<UUID>()

    private companion object {
        const val EVICT_AFTER_MILLIS = 10 * 60 * 1000L
        const val RETRY_BACKOFF_MILLIS = 5_000L
    }

    override fun getIdentifier(): String = "reslink"

    override fun getAuthor(): String = "ResidenceBridge"

    override fun getVersion(): String =
        Bukkit.getPluginManager().getPlugin("ResidenceBridge")?.description?.version ?: "unknown"

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

    /**
     * PlaceholderAPI calls this on the main thread (scoreboards, tab lists, chat), so it must never touch MySQL.
     * Serve the cached value (possibly stale, or empty on the very first request) and refresh in the background.
     */
    private fun dataFor(player: Player): CachedPlaceholderData {
        val now = System.currentTimeMillis()
        val cached = cache[player.uniqueId]
        if (cached == null || cached.expireAt <= now) {
            refreshAsync(player.uniqueId, player.name, now)
        }
        return cached ?: CachedPlaceholderData(0, emptyList(), 0L)
    }

    private fun refreshAsync(uuid: UUID, name: String, now: Long) {
        if (!refreshing.add(uuid)) return
        BridgeScheduler.runAsync {
            try {
                val list = database.listResidencesByOwner(uuid, name, 1, 256)
                val finished = System.currentTimeMillis()
                cache[uuid] = CachedPlaceholderData(
                    total = list.total,
                    names = list.entries.map { it.displayName },
                    expireAt = finished + config.placeholderCacheSeconds * 1000L
                )
                // Drop entries of players that have not been asked for in a while.
                cache.entries.removeIf { it.value.expireAt + EVICT_AFTER_MILLIS < finished }
            } catch (_: Throwable) {
                // Keep serving the previous value; retry on the next request after a short back-off.
                cache.merge(uuid, CachedPlaceholderData(0, emptyList(), now + RETRY_BACKOFF_MILLIS)) { old, _ -> old.copy(expireAt = now + RETRY_BACKOFF_MILLIS) }
            } finally {
                refreshing.remove(uuid)
            }
        }
    }

    private data class CachedPlaceholderData(
        val total: Int,
        val names: List<String>,
        val expireAt: Long
    )
}
