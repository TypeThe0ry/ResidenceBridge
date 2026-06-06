package org.ewsk.residencebridge

import com.bekvon.bukkit.residence.Residence
import com.bekvon.bukkit.residence.protection.ClaimedResidence
import org.bukkit.entity.Player
import java.util.UUID

object ResidenceHook {

    fun exists(name: String): Boolean = getResidence(name) != null

    fun getResidence(name: String): ClaimedResidence? {
        return try {
            Residence.getInstance()?.residenceManager?.getByName(name)
        } catch (_: Throwable) {
            null
        }
    }

    fun getOwnerName(name: String): String? = getResidence(name)?.owner

    fun getOwnerUuid(name: String): UUID? = getResidence(name)?.ownerUUID

    fun getWorldName(name: String): String? = getResidence(name)?.worldName ?: getResidence(name)?.world

    fun teleport(player: Player, name: String): Boolean {
        val residence = getResidence(name) ?: return false
        return try {
            residence.tpToResidence(player, player, true)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun allSnapshots(): List<ResidenceSnapshot> {
        return try {
            Residence.getInstance()?.residenceManager?.residences?.values
                ?.mapNotNull { toSnapshot(it.name) }
                ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun toSnapshot(name: String): ResidenceSnapshot? {
        val residence = getResidence(name) ?: return null
        return ResidenceSnapshot(
            name = residence.name,
            ownerUuid = residence.ownerUUID,
            ownerName = residence.owner,
            worldName = residence.worldName ?: residence.world
        )
    }
}
