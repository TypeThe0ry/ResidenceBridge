//by TypeThe0ry
package org.ewsk.residencebridge

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.Statement
import java.util.UUID
import kotlin.math.max

class BridgeDatabase(private val config: BridgeConfig) {

    private companion object {
        private const val ACTIVE_PRUNE_GRACE_MILLIS = 5 * 60 * 1000L
        private const val MAX_NAME_LENGTH = 128

        // A row owned by another server (ACTIVE or RESERVED) is never overwritten: names are globally unique.
        private fun guarded(column: String) = "$column=IF(server_id<>VALUES(server_id), $column, VALUES($column))"

        private val UPSERT_SQL = """
            INSERT INTO residence_bridge_index
              (name_key, display_name, server_id, world, tp_world, tp_x, tp_y, tp_z, tp_yaw, tp_pitch, owner_uuid, owner_name, status, updated_at)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
            ON DUPLICATE KEY UPDATE
              ${listOf("display_name", "world", "tp_world", "tp_x", "tp_y", "tp_z", "tp_yaw", "tp_pitch", "owner_uuid", "owner_name").joinToString(",\n              ") { guarded(it) }},
              status=IF(server_id<>VALUES(server_id), status, 'ACTIVE'),
              updated_at=IF(server_id<>VALUES(server_id), updated_at, VALUES(updated_at)),
              server_id=IF(server_id<>VALUES(server_id), server_id, VALUES(server_id))
            """.trimIndent()
    }

    private val dataSource: HikariDataSource

    init {
        val hikari = HikariConfig()
        hikari.jdbcUrl = "jdbc:mysql://${config.mysql.host}:${config.mysql.port}/${config.mysql.database}?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=UTC&connectTimeout=10000&socketTimeout=30000"
        hikari.username = config.mysql.username
        hikari.password = config.mysql.password
        hikari.maximumPoolSize = config.mysql.maximumPoolSize
        hikari.connectionTimeout = 10_000L
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
                tp_world VARCHAR(64),
                tp_x DOUBLE,
                tp_y DOUBLE,
                tp_z DOUBLE,
                tp_yaw FLOAT,
                tp_pitch FLOAT,
                  owner_uuid VARCHAR(36),
                  owner_name VARCHAR(32),
                status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                  updated_at BIGINT NOT NULL
                )
                """.trimIndent()
            )
            ensureColumn(conn, "residence_bridge_index", "status", "VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'")
            ensureColumn(conn, "residence_bridge_index", "tp_world", "VARCHAR(64)")
            ensureColumn(conn, "residence_bridge_index", "tp_x", "DOUBLE")
            ensureColumn(conn, "residence_bridge_index", "tp_y", "DOUBLE")
            ensureColumn(conn, "residence_bridge_index", "tp_z", "DOUBLE")
            ensureColumn(conn, "residence_bridge_index", "tp_yaw", "FLOAT")
            ensureColumn(conn, "residence_bridge_index", "tp_pitch", "FLOAT")
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS residence_bridge_pending_tp (
                  player_uuid VARCHAR(36) PRIMARY KEY,
                  player_name VARCHAR(32) NOT NULL,
                  res_name VARCHAR(128) NOT NULL,
                  target_server VARCHAR(64) NOT NULL,
                  expire_at BIGINT NOT NULL,
                  request_token VARCHAR(36)
                )
                """.trimIndent()
            )
            ensureColumn(conn, "residence_bridge_pending_tp", "request_token", "VARCHAR(36)")
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS residence_bridge_pending_action (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  player_uuid VARCHAR(36) NOT NULL,
                  player_name VARCHAR(32) NOT NULL,
                  action_type VARCHAR(32) NOT NULL,
                  command_text VARCHAR(512) NOT NULL,
                  res_name VARCHAR(128) NOT NULL,
                  target_server VARCHAR(64) NOT NULL,
                  expire_at BIGINT NOT NULL,
                  created_at BIGINT NOT NULL
                )
                """.trimIndent()
            )
            ensureIndex(conn, "residence_bridge_index", "idx_residence_bridge_owner", "owner_uuid, owner_name, status")
            ensureIndex(conn, "residence_bridge_pending_action", "idx_residence_bridge_pending_action_player", "player_uuid, target_server")
        }
    }

    fun findIndex(name: String): ResidenceIndexEntry? = connection().use { conn ->
        conn.prepareStatement("SELECT * FROM residence_bridge_index WHERE name_key=? AND status='ACTIVE'").use { ps ->
            ps.setString(1, key(name))
            ps.executeQuery().use { rs -> if (rs.next()) rs.toIndexEntry() else null }
        }
    }

    fun reserveName(name: String): Boolean = reserveName(name, config.serverId, null, null)

    fun reserveName(name: String, serverId: String, ownerUuid: UUID?, ownerName: String?): Boolean = connection().use { conn ->
        deleteStaleReservations(conn)
        insertReservation(conn, name, serverId, ownerUuid, ownerName)
    }

    /** Returns false (instead of throwing) when the name is already taken. INSERT IGNORE is avoided on purpose: it also hides truncation errors. */
    private fun insertReservation(conn: Connection, name: String, serverId: String, ownerUuid: UUID?, ownerName: String?): Boolean {
        return try {
            conn.prepareStatement(
                """
                INSERT INTO residence_bridge_index
                (name_key, display_name, server_id, world, owner_uuid, owner_name, status, updated_at)
                VALUES (?, ?, ?, NULL, ?, ?, 'RESERVED', ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, key(name))
                ps.setString(2, name)
                ps.setString(3, serverId)
                ps.setString(4, ownerUuid?.toString())
                ps.setString(5, ownerName?.take(32))
                ps.setLong(6, System.currentTimeMillis())
                ps.executeUpdate() == 1
            }
        } catch (e: SQLIntegrityConstraintViolationException) {
            false
        } catch (e: SQLException) {
            if (e.errorCode == 1062 || e.sqlState == "23000") false else throw e
        }
    }

    fun hasCreateConflict(name: String): Boolean = connection().use { conn ->
        conn.autoCommit = false
        try {
            deleteStaleReservations(conn)
            val exists = conn.prepareStatement("SELECT 1 FROM residence_bridge_index WHERE name_key=? LIMIT 1").use { ps ->
                ps.setString(1, key(name))
                ps.executeQuery().use { rs -> rs.next() }
            }
            conn.commit()
            exists
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    fun tryReserveCreate(name: String, ownerUuid: UUID, ownerName: String, maxResidences: Int): CreateReservationResult = connection().use { conn ->
        conn.autoCommit = false
        try {
            deleteStaleReservations(conn)
            val currentCount = countByOwner(conn, ownerUuid, ownerName, includeReserved = true)
            if (maxResidences != Int.MAX_VALUE && currentCount >= maxResidences) {
                conn.commit()
                return@use CreateReservationResult(CreateReservationStatus.LIMIT_REACHED, currentCount, maxResidences)
            }
            val reserved = insertReservation(conn, name, config.serverId, ownerUuid, ownerName)
            conn.commit()
            if (reserved) {
                CreateReservationResult(CreateReservationStatus.RESERVED, currentCount + 1, maxResidences)
            } else {
                CreateReservationResult(CreateReservationStatus.DUPLICATE, currentCount, maxResidences)
            }
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    /** Upserts one local snapshot. Returns the conflicting entry when another server already owns this name. */
    fun upsertSnapshot(snapshot: ResidenceSnapshot): IndexConflict? = connection().use { conn ->
        upsertSnapshots(conn, listOf(snapshot))
        findConflicts(conn, listOf(snapshot)).firstOrNull()
    }

    fun deleteReservationIfLocal(name: String) = connection().use { conn ->
        conn.prepareStatement("DELETE FROM residence_bridge_index WHERE name_key=? AND server_id=? AND status='RESERVED'").use { ps ->
            ps.setString(1, key(name))
            ps.setString(2, config.serverId)
            ps.executeUpdate()
        }
    }

    fun delete(name: String) = connection().use { conn ->
        conn.prepareStatement("DELETE FROM residence_bridge_index WHERE name_key=? AND server_id=?").use { ps ->
            ps.setString(1, key(name))
            ps.setString(2, config.serverId)
            ps.executeUpdate()
        }
    }

    fun countResidencesByOwner(ownerUuid: UUID, ownerName: String): Int = connection().use { conn ->
        countByOwner(conn, ownerUuid, ownerName, includeReserved = false)
    }

    fun listResidencesByOwner(ownerUuid: UUID, ownerName: String, page: Int, pageSize: Int): ResidenceListPage = connection().use { conn ->
        val normalizedPage = max(1, page)
        val total = conn.prepareStatement(
            """
            SELECT COUNT(*) FROM residence_bridge_index
            WHERE status='ACTIVE' AND name_key NOT LIKE '%.%'
              AND (owner_uuid=? OR (owner_uuid IS NULL AND owner_name=?))
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, ownerUuid.toString())
            ps.setString(2, ownerName)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        val maxPage = if (total <= 0) 1 else ((total - 1) / pageSize) + 1
        val safePage = normalizedPage.coerceAtMost(maxPage)
        val offset = (safePage - 1) * pageSize
        val entries = conn.prepareStatement(
            """
            SELECT * FROM residence_bridge_index
            WHERE status='ACTIVE' AND name_key NOT LIKE '%.%'
              AND (owner_uuid=? OR (owner_uuid IS NULL AND owner_name=?))
            ORDER BY server_id ASC, display_name ASC
            LIMIT ? OFFSET ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, ownerUuid.toString())
            ps.setString(2, ownerName)
            ps.setInt(3, pageSize)
            ps.setInt(4, offset)
            ps.executeQuery().use { rs ->
                val result = mutableListOf<ResidenceIndexEntry>()
                while (rs.next()) {
                    result += rs.toIndexEntry()
                }
                result
            }
        }
        ResidenceListPage(entries, total, safePage, pageSize)
    }

    fun listResidencesByOwnerName(ownerName: String, page: Int, pageSize: Int): ResidenceListPage = connection().use { conn ->
        val normalizedPage = max(1, page)
        val total = conn.prepareStatement(
            """
            SELECT COUNT(*) FROM residence_bridge_index
            WHERE status='ACTIVE' AND name_key NOT LIKE '%.%'
              AND LOWER(owner_name)=LOWER(?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, ownerName)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        val maxPage = if (total <= 0) 1 else ((total - 1) / pageSize) + 1
        val safePage = normalizedPage.coerceAtMost(maxPage)
        val offset = (safePage - 1) * pageSize
        val entries = conn.prepareStatement(
            """
            SELECT * FROM residence_bridge_index
            WHERE status='ACTIVE' AND name_key NOT LIKE '%.%'
              AND LOWER(owner_name)=LOWER(?)
            ORDER BY server_id ASC, display_name ASC
            LIMIT ? OFFSET ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, ownerName)
            ps.setInt(2, pageSize)
            ps.setInt(3, offset)
            ps.executeQuery().use { rs ->
                val result = mutableListOf<ResidenceIndexEntry>()
                while (rs.next()) {
                    result += rs.toIndexEntry()
                }
                result
            }
        }
        ResidenceListPage(entries, total, safePage, pageSize)
    }

    fun listCompletionResidenceNames(limit: Int = 500): List<String> = connection().use { conn ->
        conn.prepareStatement(
            """
            SELECT display_name FROM residence_bridge_index
            WHERE status='ACTIVE'
            ORDER BY display_name ASC
            LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                val result = mutableListOf<String>()
                while (rs.next()) {
                    result += rs.getString("display_name")
                }
                result
            }
        }
    }

    fun listCompletionResidences(limit: Int = 500): List<ResidenceIndexEntry> = connection().use { conn ->
        conn.prepareStatement(
            """
            SELECT * FROM residence_bridge_index
            WHERE status='ACTIVE'
            ORDER BY display_name ASC
            LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                val result = mutableListOf<ResidenceIndexEntry>()
                while (rs.next()) {
                    result += rs.toIndexEntry()
                }
                result
            }
        }
    }

    fun listCompletionOwnerNames(limit: Int = 500): List<String> = connection().use { conn ->
        conn.prepareStatement(
            """
            SELECT DISTINCT owner_name FROM residence_bridge_index
            WHERE status='ACTIVE' AND owner_name IS NOT NULL AND owner_name<>''
            ORDER BY owner_name ASC
            LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                val result = mutableListOf<String>()
                while (rs.next()) {
                    result += rs.getString("owner_name")
                }
                result
            }
        }
    }

    fun replaceRenamed(oldName: String, newSnapshot: ResidenceSnapshot): IndexConflict? = connection().use { conn ->
        conn.autoCommit = false
        try {
            if (key(oldName) != newSnapshot.nameKey) {
                conn.prepareStatement("DELETE FROM residence_bridge_index WHERE name_key=? AND server_id=?").use { ps ->
                    ps.setString(1, key(oldName))
                    ps.setString(2, config.serverId)
                    ps.executeUpdate()
                }
            }
            upsertSnapshots(conn, listOf(newSnapshot))
            val conflict = findConflicts(conn, listOf(newSnapshot)).firstOrNull()
            conn.commit()
            conflict
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    /**
     * Writes this server's snapshots and (optionally) prunes rows that disappeared.
     * An empty snapshot list never prunes: it almost always means Residence was not readable,
     * and deleting the whole index would let other servers claim every name.
     */
    fun syncServerSnapshots(snapshots: List<ResidenceSnapshot>): SyncOutcome = connection().use { conn ->
        conn.autoCommit = false
        try {
            val now = System.currentTimeMillis()
            val valid = snapshots.filter { it.nameKey.length <= MAX_NAME_LENGTH && it.name.length <= MAX_NAME_LENGTH }
            val skipped = snapshots.size - valid.size
            deleteStaleReservations(conn)
            upsertSnapshots(conn, valid)
            purgeExpiredPending(conn, now)
            val conflicts = findConflicts(conn, valid)
            var pruned = 0
            val pruneSkipped = valid.isEmpty()
            if (!pruneSkipped) {
                pruned = conn.prepareStatement("DELETE FROM residence_bridge_index WHERE server_id=? AND status='ACTIVE' AND updated_at<?").use { ps ->
                    ps.setString(1, config.serverId)
                    ps.setLong(2, now - ACTIVE_PRUNE_GRACE_MILLIS)
                    ps.executeUpdate()
                }
            }
            conn.commit()
            SyncOutcome(valid.size, skipped, pruned, pruneSkipped, conflicts)
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    fun writePending(playerUuid: UUID, playerName: String, resName: String, targetServer: String, expireAt: Long): PendingTransferIdentity.Teleport = connection().use { conn ->
        val requestToken = UUID.randomUUID().toString()
        conn.prepareStatement(
            """
            INSERT INTO residence_bridge_pending_tp
            (player_uuid, player_name, res_name, target_server, expire_at, request_token)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              player_name=VALUES(player_name),
              res_name=VALUES(res_name),
              target_server=VALUES(target_server),
              expire_at=VALUES(expire_at),
              request_token=VALUES(request_token)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, playerUuid.toString())
            ps.setString(2, playerName)
            ps.setString(3, resName)
            ps.setString(4, targetServer)
            ps.setLong(5, expireAt)
            ps.setString(6, requestToken)
            ps.executeUpdate()
        }
        PendingTransferIdentity.Teleport(playerUuid, requestToken)
    }

    fun writePendingAction(playerUuid: UUID, playerName: String, actionType: String, commandText: String, resName: String, targetServer: String, expireAt: Long): PendingTransferIdentity.Action = connection().use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO residence_bridge_pending_action
            (player_uuid, player_name, action_type, command_text, res_name, target_server, expire_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS
        ).use { ps ->
            ps.setString(1, playerUuid.toString())
            ps.setString(2, playerName)
            ps.setString(3, actionType)
            ps.setString(4, commandText.take(512))
            ps.setString(5, resName)
            ps.setString(6, targetServer)
            ps.setLong(7, expireAt)
            ps.setLong(8, System.currentTimeMillis())
            ps.executeUpdate()
            ps.generatedKeys.use { keys ->
                check(keys.next()) { "Pending action insert did not return an id" }
                PendingTransferIdentity.Action(keys.getLong(1))
            }
        }
    }

    fun deletePendingTransfer(identity: PendingTransferIdentity) = connection().use { conn ->
        when (identity) {
            is PendingTransferIdentity.Teleport -> conn.prepareStatement(
                "DELETE FROM residence_bridge_pending_tp WHERE player_uuid=? AND request_token=?"
            ).use { ps ->
                ps.setString(1, identity.playerUuid.toString())
                ps.setString(2, identity.requestToken)
                ps.executeUpdate()
            }
            is PendingTransferIdentity.Action -> conn.prepareStatement(
                "DELETE FROM residence_bridge_pending_action WHERE id=?"
            ).use { ps ->
                ps.setLong(1, identity.id)
                ps.executeUpdate()
            }
        }
    }

    fun consumePending(playerUuid: UUID): PendingTeleport? = connection().use { conn ->
        conn.autoCommit = false
        try {
            val pending = conn.prepareStatement(
                "SELECT * FROM residence_bridge_pending_tp WHERE player_uuid=? AND target_server=? FOR UPDATE"
            ).use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.setString(2, config.serverId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toPendingTeleport() else null }
            }
            if (pending != null) {
                conn.prepareStatement("DELETE FROM residence_bridge_pending_tp WHERE player_uuid=? AND target_server=?").use { ps ->
                    ps.setString(1, playerUuid.toString())
                    ps.setString(2, config.serverId)
                    ps.executeUpdate()
                }
            }
            conn.commit()
            pending?.takeIf { it.expireAt >= System.currentTimeMillis() }
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    fun consumePendingActions(playerUuid: UUID): List<PendingAction> = connection().use { conn ->
        conn.autoCommit = false
        try {
            val now = System.currentTimeMillis()
            val actions = conn.prepareStatement(
                """
                SELECT * FROM residence_bridge_pending_action
                WHERE player_uuid=? AND target_server=? AND expire_at>=?
                ORDER BY id ASC FOR UPDATE
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.setString(2, config.serverId)
                ps.setLong(3, now)
                ps.executeQuery().use { rs ->
                    val result = mutableListOf<PendingAction>()
                    while (rs.next()) {
                        result += rs.toPendingAction()
                    }
                    result
                }
            }
            if (actions.isNotEmpty()) {
                val marks = actions.joinToString(",") { "?" }
                conn.prepareStatement("DELETE FROM residence_bridge_pending_action WHERE id IN ($marks)").use { ps ->
                    actions.forEachIndexed { index, action -> ps.setLong(index + 1, action.id) }
                    ps.executeUpdate()
                }
            }
            conn.prepareStatement("DELETE FROM residence_bridge_pending_action WHERE player_uuid=? AND expire_at<?").use { ps ->
                ps.setString(1, playerUuid.toString())
                ps.setLong(2, now)
                ps.executeUpdate()
            }
            conn.commit()
            actions
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

    fun purgeExpiredPending(now: Long = System.currentTimeMillis()) = connection().use { conn ->
        purgeExpiredPending(conn, now)
    }

    private fun purgeExpiredPending(conn: Connection, now: Long) {
        conn.prepareStatement("DELETE FROM residence_bridge_pending_tp WHERE expire_at<?").use { ps ->
            ps.setLong(1, now)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM residence_bridge_pending_action WHERE expire_at<?").use { ps ->
            ps.setLong(1, now)
            ps.executeUpdate()
        }
    }

    private fun upsertSnapshots(conn: Connection, snapshots: List<ResidenceSnapshot>) {
        if (snapshots.isEmpty()) return
        val now = System.currentTimeMillis()
        conn.prepareStatement(UPSERT_SQL).use { ps ->
            var queued = 0
            snapshots.forEach { snapshot ->
                ps.setString(1, snapshot.nameKey)
                ps.setString(2, snapshot.name)
                ps.setString(3, config.serverId)
                ps.setString(4, snapshot.worldName?.take(64))
                val teleport = snapshot.teleportLocation
                ps.setString(5, teleport?.worldName?.take(64))
                if (teleport == null) {
                    ps.setNull(6, java.sql.Types.DOUBLE)
                    ps.setNull(7, java.sql.Types.DOUBLE)
                    ps.setNull(8, java.sql.Types.DOUBLE)
                    ps.setNull(9, java.sql.Types.FLOAT)
                    ps.setNull(10, java.sql.Types.FLOAT)
                } else {
                    ps.setDouble(6, teleport.x)
                    ps.setDouble(7, teleport.y)
                    ps.setDouble(8, teleport.z)
                    ps.setFloat(9, teleport.yaw)
                    ps.setFloat(10, teleport.pitch)
                }
                ps.setString(11, snapshot.ownerUuid?.toString())
                ps.setString(12, snapshot.ownerName?.take(32))
                ps.setLong(13, now)
                ps.addBatch()
                if (++queued % 500 == 0) ps.executeBatch()
            }
            ps.executeBatch()
        }
    }

    /**
     * Finds snapshots whose name is currently claimed by a different server. Checked with an explicit SELECT
     * because the affected-row count of INSERT .. ON DUPLICATE KEY UPDATE is ambiguous with Connector/J's found-rows default.
     */
    private fun findConflicts(conn: Connection, snapshots: List<ResidenceSnapshot>): List<IndexConflict> {
        val result = mutableListOf<IndexConflict>()
        snapshots.map { it.nameKey }.distinct().chunked(400).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            conn.prepareStatement("SELECT name_key, display_name, server_id, status FROM residence_bridge_index WHERE server_id<>? AND name_key IN ($marks)").use { ps ->
                ps.setString(1, config.serverId)
                chunk.forEachIndexed { index, nameKey -> ps.setString(index + 2, nameKey) }
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        result += IndexConflict(rs.getString("name_key"), rs.getString("display_name"), rs.getString("server_id"), rs.getString("status"))
                    }
                }
            }
        }
        return result
    }

    private fun countByOwner(conn: Connection, ownerUuid: UUID, ownerName: String, includeReserved: Boolean): Int {
        val statusSql = if (includeReserved) "status IN ('ACTIVE','RESERVED')" else "status='ACTIVE'"
        return conn.prepareStatement(
            """
            SELECT COUNT(*) FROM residence_bridge_index
            WHERE $statusSql AND name_key NOT LIKE '%.%'
              AND (owner_uuid=? OR (owner_uuid IS NULL AND owner_name=?))
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, ownerUuid.toString())
            ps.setString(2, ownerName)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private fun deleteStaleReservations(conn: Connection) {
        conn.prepareStatement("DELETE FROM residence_bridge_index WHERE status='RESERVED' AND updated_at<?").use { ps ->
            ps.setLong(1, System.currentTimeMillis() - 10 * 60 * 1000L)
            ps.executeUpdate()
        }
    }

    private fun ensureColumn(conn: Connection, table: String, column: String, definition: String) {
        val exists = conn.metaData.getColumns(conn.catalog, null, table, column).use { it.next() }
        if (!exists) {
            conn.createStatement().use { it.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition") }
        }
    }

    private fun ensureIndex(conn: Connection, table: String, index: String, columns: String) {
        val exists = conn.metaData.getIndexInfo(conn.catalog, null, table, false, false).use { rs ->
            var found = false
            while (rs.next()) {
                if (index.equals(rs.getString("INDEX_NAME"), ignoreCase = true)) {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) {
            conn.createStatement().use { it.executeUpdate("CREATE INDEX $index ON $table($columns)") }
        }
    }

    private fun connection(): Connection = dataSource.connection

    private fun ResultSet.toIndexEntry(): ResidenceIndexEntry {
        return ResidenceIndexEntry(
            nameKey = getString("name_key"),
            displayName = getString("display_name"),
            serverId = getString("server_id"),
            worldName = getString("world"),
            ownerUuid = getString("owner_uuid")?.let { runCatching { UUID.fromString(it) }.getOrNull() },
            ownerName = getString("owner_name"),
            updatedAt = getLong("updated_at"),
            teleportLocation = readBridgeLocation()
        )
    }

    private fun ResultSet.readBridgeLocation(): BridgeLocation? {
        val world = getString("tp_world") ?: return null
        val x = getDouble("tp_x")
        if (wasNull()) return null
        val y = getDouble("tp_y")
        if (wasNull()) return null
        val z = getDouble("tp_z")
        if (wasNull()) return null
        val yaw = getFloat("tp_yaw").let { if (wasNull()) 0f else it }
        val pitch = getFloat("tp_pitch").let { if (wasNull()) 0f else it }
        return BridgeLocation(world, x, y, z, yaw, pitch)
    }

    private fun ResultSet.toPendingTeleport(): PendingTeleport {
        return PendingTeleport(
            playerUuid = UUID.fromString(getString("player_uuid")),
            playerName = getString("player_name"),
            residenceName = getString("res_name"),
            targetServer = getString("target_server"),
            expireAt = getLong("expire_at"),
            requestToken = getString("request_token")
        )
    }

    private fun ResultSet.toPendingAction(): PendingAction {
        return PendingAction(
            id = getLong("id"),
            playerUuid = UUID.fromString(getString("player_uuid")),
            playerName = getString("player_name"),
            actionType = getString("action_type"),
            commandText = getString("command_text"),
            residenceName = getString("res_name"),
            targetServer = getString("target_server"),
            expireAt = getLong("expire_at")
        )
    }
}
