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

    override fun getIdentifier(): String = "reslink"

    override fun getAuthor(): String = "ResidenceBridge"

    override fun getVersion(): String = "1.2.0"

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
        val cached = cache[player.uniqueId]
        if (cached != null && cached.expireAt > now) {
            return cached
        }
        val list = database.listResidencesByOwner(player.uniqueId, player.name, 1, 256)
        val data = CachedPlaceholderData(
            total = list.total,
            names = list.entries.map { it.displayName },
            expireAt = now + config.placeholderCacheSeconds * 1000L
        )
        cache[player.uniqueId] = data
        return data
    }

    private data class CachedPlaceholderData(
        val total: Int,
        val names: List<String>,
        val expireAt: Long
    )
}