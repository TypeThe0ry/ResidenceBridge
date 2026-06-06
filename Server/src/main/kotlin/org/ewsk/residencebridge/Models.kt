package org.ewsk.residencebridge

import java.util.UUID

data class ResidenceSnapshot(
    val name: String,
    val ownerUuid: UUID?,
    val ownerName: String?,
    val worldName: String?,
    val teleportLocation: BridgeLocation? = null
) {
    val nameKey: String = key(name)
}

data class BridgeLocation(
    val worldName: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f
)

data class ResidenceIndexEntry(
    val nameKey: String,
    val displayName: String,
    val serverId: String,
    val worldName: String?,
    val ownerUuid: UUID?,
    val ownerName: String?,
    val updatedAt: Long,
    val teleportLocation: BridgeLocation? = null
)

data class PendingTeleport(
    val playerUuid: UUID,
    val playerName: String,
    val residenceName: String,
    val targetServer: String,
    val expireAt: Long
)

data class PendingAction(
    val id: Long,
    val playerUuid: UUID,
    val playerName: String,
    val actionType: String,
    val commandText: String,
    val residenceName: String,
    val targetServer: String,
    val expireAt: Long
)

data class ResidenceListPage(
    val entries: List<ResidenceIndexEntry>,
    val total: Int,
    val page: Int,
    val pageSize: Int
) {
    val maxPage: Int = if (total <= 0) 1 else ((total - 1) / pageSize) + 1
}

enum class CreateReservationStatus {
    RESERVED,
    DUPLICATE,
    LIMIT_REACHED
}

data class CreateReservationResult(
    val status: CreateReservationStatus,
    val count: Int,
    val max: Int
) {
    val reserved: Boolean = status == CreateReservationStatus.RESERVED
}

fun key(name: String): String = name.trim().lowercase(java.util.Locale.ROOT)
