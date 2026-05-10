package org.ewsk.residencebridge

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

class BridgeDatabase(private val config: BridgeConfig) {

    private val dataSource: HikariDataSource

    init {
        val hikari = HikariConfig()
        hikari.jdbcUrl = "jdbc:mysql://${config.mysql.host}:${config.mysql.port}/${config.mysql.database}?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=UTC"
        hikari.username = config.mysql.username
        hikari.password = config.mysql.password
        hikari.maximumPoolSize = config.mysql.maximumPoolSize
        hikari.poolName = "ResidenceBridge"
        hikari.driverClassName = "com.mysql.cj.jdbc.Driver"
        dataSource = HikariDataSource(hikari)
    }

    fun initTables() = connection().use { conn ->
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS residence_bridge_index (
                  name_key VARCHAR(128) PRIMARY KEY,
                  display_name VARCHAR(128) NOT NULL,
                  server_id VARCHAR(64) NOT NULL,
                  world VARCHAR(64),
                  owner_uuid VARCHAR(36),
                  owner_name VARCHAR(32),
                  updated_at BIGINT NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS residence_bridge_pending_tp (
                  player_uuid VARCHAR(36) PRIMARY KEY,
                  player_name VARCHAR(32) NOT NULL,
                  res_name VARCHAR(128) NOT NULL,
                  target_server VARCHAR(64) NOT NULL,
                  expire_at BIGINT NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    fun findIndex(name: String): ResidenceIndexEntry? = connection().use { conn ->
        conn.prepareStatement("SELECT * FROM residence_bridge_index WHERE name_key=?").use { ps ->
            ps.setString(1, key(name))
            ps.executeQuery().use { rs -> if (rs.next()) rs.toIndexEntry() else null }
        }
    }

    fun reserveName(name: String): Boolean = connection().use { conn ->
        conn.prepareStatement(
            """
            INSERT IGNORE INTO residence_bridge_index
            (name_key, display_name, server_id, world, owner_uuid, owner_name, updated_at)
            VALUES (?, ?, ?, NULL, NULL, NULL, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, key(name))
            ps.setString(2, name)
            ps.setString(3, config.serverId)
            ps.setLong(4, System.currentTimeMillis())
            ps.executeUpdate() == 1
        }
    }

    fun upsertSnapshot(snapshot: ResidenceSnapshot) = connection().use { conn ->
        upsertSnapshot(conn, snapshot)
    }

    fun deleteReservationIfLocal(name: String) = connection().use { conn ->
        conn.prepareStatement("DELETE FROM residence_bridge_index WHERE name_key=? AND server_id=?").use { ps ->
            ps.setString(1, key(name))
            ps.setString(2, config.serverId)
            ps.executeUpdate()
        }
    }

    fun delete(name: String) = connection().use { conn ->
        conn.prepareStatement("DELETE FROM residence_bridge_index WHERE name_key=?").use { ps ->
            ps.setString(1, key(name))
            ps.executeUpdate()
        }
    }

    fun replaceRenamed(oldName: String, newSnapshot: ResidenceSnapshot) = connection().use { conn ->
        conn.autoCommit = false
        try {
            if (key(oldName) != newSnapshot.nameKey) {
                conn.prepareStatement("DELETE FROM residence_bridge_index WHERE name_key=?").use { ps ->
                    ps.setString(1, key(oldName))
                    ps.executeUpdate()
                }
            }
            upsertSnapshot(conn, newSnapshot)
            conn.commit()
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    fun syncServerSnapshots(snapshots: List<ResidenceSnapshot>) = connection().use { conn ->
        conn.autoCommit = false
        try {
            snapshots.forEach { upsertSnapshot(conn, it) }
            if (snapshots.isEmpty()) {
                conn.prepareStatement("DELETE FROM residence_bridge_index WHERE server_id=?").use { ps ->
                    ps.setString(1, config.serverId)
                    ps.executeUpdate()
                }
            } else {
                val marks = snapshots.joinToString(",") { "?" }
                conn.prepareStatement("DELETE FROM residence_bridge_index WHERE server_id=? AND name_key NOT IN ($marks)").use { ps ->
                    ps.setString(1, config.serverId)
                    snapshots.forEachIndexed { index, snapshot -> ps.setString(index + 2, snapshot.nameKey) }
                    ps.executeUpdate()
                }
            }
            conn.commit()
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    fun writePending(playerUuid: UUID, playerName: String, resName: String, targetServer: String, expireAt: Long) = connection().use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO residence_bridge_pending_tp
            (player_uuid, player_name, res_name, target_server, expire_at)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              player_name=VALUES(player_name),
              res_name=VALUES(res_name),
              target_server=VALUES(target_server),
              expire_at=VALUES(expire_at)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, playerUuid.toString())
            ps.setString(2, playerName)
            ps.setString(3, resName)
            ps.setString(4, targetServer)
            ps.setLong(5, expireAt)
            ps.executeUpdate()
        }
    }

    fun consumePending(playerUuid: UUID): PendingTeleport? = connection().use { conn ->
        conn.autoCommit = false
        try {
            val pending = conn.prepareStatement("SELECT * FROM residence_bridge_pending_tp WHERE player_uuid=?").use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.executeQuery().use { rs -> if (rs.next()) rs.toPendingTeleport() else null }
            }
            conn.prepareStatement("DELETE FROM residence_bridge_pending_tp WHERE player_uuid=?").use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.executeUpdate()
            }
            conn.commit()
            pending?.takeIf { it.expireAt >= System.currentTimeMillis() && it.targetServer.equals(config.serverId, ignoreCase = true) }
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    fun close() {
        dataSource.close()
    }

    private fun upsertSnapshot(conn: Connection, snapshot: ResidenceSnapshot) {
        conn.prepareStatement(
            """
            INSERT INTO residence_bridge_index
            (name_key, display_name, server_id, world, owner_uuid, owner_name, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              display_name=VALUES(display_name),
              server_id=VALUES(server_id),
              world=VALUES(world),
              owner_uuid=VALUES(owner_uuid),
              owner_name=VALUES(owner_name),
              updated_at=VALUES(updated_at)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, snapshot.nameKey)
            ps.setString(2, snapshot.name)
            ps.setString(3, config.serverId)
            ps.setString(4, snapshot.worldName)
            ps.setString(5, snapshot.ownerUuid?.toString())
            ps.setString(6, snapshot.ownerName)
            ps.setLong(7, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    private fun connection(): Connection = dataSource.connection

    private fun ResultSet.toIndexEntry(): ResidenceIndexEntry {
        return ResidenceIndexEntry(
            nameKey = getString("name_key"),
            displayName = getString("display_name"),
            serverId = getString("server_id"),
            worldName = getString("world"),
            ownerUuid = getString("owner_uuid")?.let { UUID.fromString(it) },
            ownerName = getString("owner_name"),
            updatedAt = getLong("updated_at")
        )
    }

    private fun ResultSet.toPendingTeleport(): PendingTeleport {
        return PendingTeleport(
            playerUuid = UUID.fromString(getString("player_uuid")),
            playerName = getString("player_name"),
            residenceName = getString("res_name"),
            targetServer = getString("target_server"),
            expireAt = getLong("expire_at")
        )
    }
}
