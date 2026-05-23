package org.ewsk.residencebridge

import java.util.UUID

data class ResidenceSnapshot(
    val name: String,
    val ownerUuid: UUID?,
    val ownerName: String?,
    val worldName: String?
) {
    val nameKey: String = key(name)
}

data class ResidenceIndexEntry(
    val nameKey: String,
    val displayName: String,
    val serverId: String,
    val worldName: String?,
    val ownerUuid: UUID?,
    val ownerName: String?,
    val updatedAt: Long
)

data class PendingTeleport(
    val playerUuid: UUID,
    val playerName: String,
    val residenceName: String,
    val targetServer: String,
    val expireAt: Long
)

fun key(name: String): String = name.trim().lowercase(java.util.Locale.ROOT)
