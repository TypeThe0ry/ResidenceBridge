//by TypeThe0ry
package org.ewsk.residencebridge

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import kotlin.math.max

class BridgeDatabase(private val config: BridgeConfig) {

    private companion object {
        private const val ACTIVE_PRUNE_GRACE_MILLIS = 5 * 60 * 1000L
        private const val UPSERT_SNAPSHOT_SQL = """
            INSERT INTO residence_bridge_index
              (name_key, display_name, server_id, world, tp_world, tp_x, tp_y, tp_z, tp_yaw, tp_pitch, owner_uuid, owner_name, status, updated_at)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
            ON DUPLICATE KEY UPDATE
                            display_name=IF(status='ACTIVE' AND server_id<>VALUES(server_id), display_name, VALUES(display_name)),
                            server_id=IF(status='ACTIVE' AND server_id<>VALUES(server_id), server_id, VALUES(server_id)),
                            world=IF(status='ACTIVE' AND server_id<>VALUES(server_id), world, VALUES(world)),
                            tp_world=IF(status='ACTIVE' AND server_id<>VALUES(server_id), tp_world, VALUES(tp_world)),
                            tp_x=IF(status='ACTIVE' AND server_id<>VALUES(server_id), tp_x, VALUES(tp_x)),
                            tp_y=IF(status='ACTIVE' AND server_id<>VALUES(server_id), tp_y, VALUES(tp_y)),
                            tp_z=IF(status='ACTIVE' AND server_id<>VALUES(server_id), tp_z, VALUES(tp_z)),
                            tp_yaw=IF(status='ACTIVE' AND server_id<>VALUES(server_id), tp_yaw, VALUES(tp_yaw)),
                            tp_pitch=IF(status='ACTIVE' AND server_id<>VALUES(server_id), tp_pitch, VALUES(tp_pitch)),
                            owner_uuid=IF(status='ACTIVE' AND server_id<>VALUES(server_id), owner_uuid, VALUES(owner_uuid)),
                            owner_name=IF(status='ACTIVE' AND server_id<>VALUES(server_id), owner_name, VALUES(owner_name)),
                            status=IF(status='ACTIVE' AND server_id<>VALUES(server_id), status, 'ACTIVE'),
                            updated_at=IF(status='ACTIVE' AND server_id<>VALUES(server_id), updated_at, VALUES(updated_at))
        """
    }

    private val dataSource: HikariDataSource

    init {
        val hikari = HikariConfig()
        hikari.jdbcUrl = "jdbc:mysql://${config.mysql.host}:${config.mysql.port}/${config.mysql.database}" +
            "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=UTC" +
            "&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048" +
            "&useServerPrepStmts=true&rewriteBatchedStatements=true&useLocalSessionState=true"
        hikari.username = config.mysql.username
        hikari.password = config.mysql.password
        hikari.maximumPoolSize = config.mysql.maximumPoolSize
        hikari.minimumIdle = (config.mysql.maximumPoolSize / 2).coerceAtLeast(1)
        hikari.connectionTimeout = 10_000L
        hikari.idleTimeout = 10 * 60 * 1000L
        hikari.maxLifetime = 30 * 60 * 1000L
        hikari.leakDetectionThreshold = 60 * 1000L
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
                  expire_at BIGINT NOT NULL
                )
                """.trimIndent()
            )
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
            ensureIndex(conn, "residence_bridge_index", "idx_residence_bridge_sync", "server_id, status, updated_at")
            ensureIndex(conn, "residence_bridge_index", "idx_residence_bridge_owner_name", "owner_name, status")
            ensureIndex(conn, "residence_bridge_pending_action", "idx_residence_bridge_pending_action_player", "player_uuid, target_server")
        }
    }

    fun findIndex(name: String): ResidenceIndexEntry? = connection().use { conn ->
        conn.prepareStatement("SELECT * FROM residence_bridge_index WHERE name_key=? AND status='ACTIVE'").use { ps ->
            ps.setString(1, key(name))
            ps.executeQuery().use { rs -> if (rs.next()) rs.toIndexEntry() else null }
        }
    }

    fun reserveName(name: String, serverId: String, ownerUuid: UUID?, ownerName: String?): Boolean = connection().use { conn ->
        conn.prepareStatement(
            """
            INSERT IGNORE INTO residence_bridge_index
            (name_key, display_name, server_id, world, owner_uuid, owner_name, status, updated_at)
            VALUES (?, ?, ?, NULL, ?, ?, 'RESERVED', ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, key(name))
            ps.setString(2, name)
            ps.setString(3, serverId)
            ps.setString(4, ownerUuid?.toString())
            ps.setString(5, ownerName)
            ps.setLong(6, System.currentTimeMillis())
            ps.executeUpdate() == 1
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
            val reserved = conn.prepareStatement(
                """
                INSERT IGNORE INTO residence_bridge_index
                (name_key, display_name, server_id, world, owner_uuid, owner_name, status, updated_at)
                VALUES (?, ?, ?, NULL, ?, ?, 'RESERVED', ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, key(name))
                ps.setString(2, name)
                ps.setString(3, config.serverId)
                ps.setString(4, ownerUuid.toString())
                ps.setString(5, ownerName)
                ps.setLong(6, System.currentTimeMillis())
                ps.executeUpdate() == 1
            }
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

    fun bulkUpsertSnapshots(snapshots: List<ResidenceSnapshot>) = connection().use { conn ->
        if (snapshots.isEmpty()) {
            return@use
        }
        conn.autoCommit = false
        try {
            conn.prepareStatement(UPSERT_SNAPSHOT_SQL).use { ps ->
                snapshots.forEach { snapshot ->
                    bindSnapshot(ps, snapshot)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    fun bulkDelete(nameKeys: List<String>) = connection().use { conn ->
        if (nameKeys.isEmpty()) {
            return@use
        }
        conn.autoCommit = false
        try {
            for (chunk in nameKeys.chunked(500)) {
                val marks = chunk.joinToString(",") { "?" }
                conn.prepareStatement("DELETE FROM residence_bridge_index WHERE name_key IN ($marks)").use { ps ->
                    chunk.forEachIndexed { index, nameKey -> ps.setString(index + 1, nameKey) }
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

    fun deleteReservationIfLocal(name: String) = connection().use { conn ->
        conn.prepareStatement("DELETE FROM residence_bridge_index WHERE name_key=? AND server_id=? AND status='RESERVED'").use { ps ->
            ps.setString(1, key(name))
            ps.setString(2, config.serverId)
            ps.executeUpdate()
        }
    }

    fun listResidencesByOwner(ownerUuid: UUID, ownerName: String, page: Int, pageSize: Int): ResidenceListPage = connection().use { conn ->
        listPageByOwner(conn, ownerUuid.toString(), ownerName, page, pageSize)
    }

    /**
     * 按 owner_name 查询。MySQL 默认 collation（如 utf8mb4_0900_ai_ci / utf8_general_ci）本身不区分大小写，
     * 直接使用 owner_name=? 可命中 idx_residence_bridge_owner_name 索引（LOWER() 会使其失效）。
     */
    fun listResidencesByOwnerName(ownerName: String, page: Int, pageSize: Int): ResidenceListPage = connection().use { conn ->
        listPageByOwner(conn, null, ownerName, page, pageSize)
    }

    /**
     * 单条 SQL 完成分页 + 总数统计（COUNT(*) OVER()），避免 COUNT 与 SELECT 两次往返。
     * page 越界时仅重查最后一页。
     */
    private fun listPageByOwner(conn: Connection, ownerUuid: String?, ownerName: String, page: Int, pageSize: Int): ResidenceListPage {
        val whereSql = if (ownerUuid != null) {
            "status='ACTIVE' AND (owner_uuid=? OR (owner_uuid IS NULL AND owner_name=?))"
        } else {
            "status='ACTIVE' AND owner_name=?"
        }
        fun query(targetPage: Int): ResidenceListPage {
            val offset = (targetPage - 1) * pageSize
            var total = 0
            val entries = conn.prepareStatement(
                """
                SELECT *, COUNT(*) OVER() AS total_count FROM residence_bridge_index
                WHERE $whereSql
                ORDER BY server_id ASC, display_name ASC
                LIMIT ? OFFSET ?
                """.trimIndent()
            ).use { ps ->
                if (ownerUuid != null) {
                    ps.setString(1, ownerUuid)
                    ps.setString(2, ownerName)
                } else {
                    ps.setString(1, ownerName)
                }
                ps.setInt(if (ownerUuid != null) 3 else 2, pageSize)
                ps.setInt(if (ownerUuid != null) 4 else 3, offset)
                ps.executeQuery().use { rs ->
                    val result = mutableListOf<ResidenceIndexEntry>()
                    while (rs.next()) {
                        if (total == 0) {
                            total = rs.getInt("total_count")
                        }
                        result += rs.toIndexEntry()
                    }
                    result
                }
            }
            return ResidenceListPage(entries, total, targetPage, pageSize)
        }
        val normalizedPage = max(1, page)
        val result = query(normalizedPage)
        val maxPage = if (result.total <= 0) 1 else ((result.total - 1) / pageSize) + 1
        return if (result.entries.isNotEmpty() || result.total == 0 || normalizedPage <= maxPage) {
            result.copy(page = normalizedPage.coerceAtMost(maxPage))
        } else {
            query(maxPage)
        }
    }

    /**
     * 补全缓存的唯一数据来源。只选补全真正需要的列（避免把 tp_* 坐标一起拉回来），
     * owner 名单由调用方从同一批结果里推导，不再额外发 DISTINCT 查询。
     */
    fun listCompletionResidences(limit: Int = 500): List<ResidenceIndexEntry> = connection().use { conn ->
        conn.prepareStatement(
            """
            SELECT name_key, display_name, server_id, world, owner_uuid, owner_name, updated_at
            FROM residence_bridge_index
            WHERE status='ACTIVE'
            ORDER BY display_name ASC
            LIMIT ?
            """.trimIndent()
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                val result = mutableListOf<ResidenceIndexEntry>()
                while (rs.next()) {
                    result += ResidenceIndexEntry(
                        nameKey = rs.getString("name_key"),
                        displayName = rs.getString("display_name"),
                        serverId = rs.getString("server_id"),
                        worldName = rs.getString("world"),
                        ownerUuid = rs.getString("owner_uuid")?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                        ownerName = rs.getString("owner_name"),
                        updatedAt = rs.getLong("updated_at")
                    )
                }
                result
            }
        }
    }

    /**
     * 轻量版本指纹（行数 + 最大更新时间），用于判断 completion 缓存是否需要刷新。
     *
     * 注意：不能用 `count shl 32 or maxUpdatedAt`。updated_at 是毫秒时间戳，已占约 41 位，
     * 与左移后的 count 位区间重叠，会出现「行数变了但版本号不变」的漏刷新。
     * 这里改用混合哈希，两个分量都参与且互不覆盖。
     */
    fun completionVersion(): Long = connection().use { conn ->
        conn.prepareStatement("SELECT COUNT(*), COALESCE(MAX(updated_at), 0) FROM residence_bridge_index").use { ps ->
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val count = rs.getLong(1)
                    val maxUpdatedAt = rs.getLong(2)
                    var hash = count * 0x9E3779B97F4A7C15uL.toLong()
                    hash = hash xor (maxUpdatedAt * 0xC2B2AE3D27D4EB4FuL.toLong())
                    hash xor (hash ushr 29)
                } else {
                    0L
                }
            }
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

    /**
     * 增量同步：只 upsert [changed]（指纹对比后有变化的快照），
     * 剪枝时以 [knownKeys]（本服当前全部 name_key）为基准删除本服已不存在的记录。
     */
    fun syncServerSnapshots(changed: List<ResidenceSnapshot>, knownKeys: List<String>) = connection().use { conn ->
        conn.autoCommit = false
        try {
            if (changed.isNotEmpty()) {
                conn.prepareStatement(UPSERT_SNAPSHOT_SQL).use { ps ->
                    changed.forEach { snapshot ->
                        bindSnapshot(ps, snapshot)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
            val pruneBefore = System.currentTimeMillis() - ACTIVE_PRUNE_GRACE_MILLIS
            pruneStale(conn, pruneBefore, knownKeys)
            conn.commit()
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    /**
     * 剪枝：先按 (server_id, status, updated_at) 选出本服可能过期的候选行，
     * 与 knownKeys 求差集后分批删除，避免超长 NOT IN 列表导致 SQL 过大或参数超限。
     */
    private fun pruneStale(conn: Connection, pruneBefore: Long, knownKeys: List<String>) {
        if (knownKeys.isEmpty()) {
            conn.prepareStatement("DELETE FROM residence_bridge_index WHERE server_id=? AND status='ACTIVE' AND updated_at<?").use { ps ->
                ps.setString(1, config.serverId)
                ps.setLong(2, pruneBefore)
                ps.executeUpdate()
            }
            return
        }
        val candidates = mutableListOf<String>()
        conn.prepareStatement("SELECT name_key FROM residence_bridge_index WHERE server_id=? AND status='ACTIVE' AND updated_at<?").use { ps ->
            ps.setString(1, config.serverId)
            ps.setLong(2, pruneBefore)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    candidates += rs.getString(1)
                }
            }
        }
        if (candidates.isEmpty()) {
            return
        }
        val knownSet = HashSet<String>(knownKeys.size)
        knownSet.addAll(knownKeys)
        for (chunk in candidates.asSequence().filterNot { it in knownSet }.chunked(500)) {
            val staleKeys = chunk.toList()
            if (staleKeys.isEmpty()) {
                continue
            }
            val marks = staleKeys.joinToString(",") { "?" }
            conn.prepareStatement("DELETE FROM residence_bridge_index WHERE name_key IN ($marks)").use { ps ->
                staleKeys.forEachIndexed { index, nameKey -> ps.setString(index + 1, nameKey) }
                ps.executeUpdate()
            }
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

    fun writePendingAction(playerUuid: UUID, playerName: String, actionType: String, commandText: String, resName: String, targetServer: String, expireAt: Long) = connection().use { conn ->
        conn.prepareStatement(
            """
            INSERT INTO residence_bridge_pending_action
            (player_uuid, player_name, action_type, command_text, res_name, target_server, expire_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
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
        }
    }

    /**
     * 玩家入服时一次性消费待传送与待执行动作，复用同一条连接/事务，
     * 避免两次独立连接往返。
     */
    fun consumePendingForJoin(playerUuid: UUID): PendingJoinData = connection().use { conn ->
        conn.autoCommit = false
        try {
            val pending = consumePendingTeleport(conn, playerUuid)
            val actions = consumePendingActions(conn, playerUuid)
            conn.commit()
            PendingJoinData(pending, actions)
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    private fun consumePendingTeleport(conn: Connection, playerUuid: UUID): PendingTeleport? {
        val pending = conn.prepareStatement(
            "SELECT * FROM residence_bridge_pending_tp WHERE player_uuid=? AND target_server=?"
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
        return pending?.takeIf { it.expireAt >= System.currentTimeMillis() }
    }

    private fun consumePendingActions(conn: Connection, playerUuid: UUID): List<PendingAction> {
        val now = System.currentTimeMillis()
        val actions = conn.prepareStatement(
            """
            SELECT * FROM residence_bridge_pending_action
            WHERE player_uuid=? AND target_server=? AND expire_at>=?
            ORDER BY id ASC
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
        return actions
    }

    fun close() {
        dataSource.close()
    }

    private fun upsertSnapshot(conn: Connection, snapshot: ResidenceSnapshot) {
        conn.prepareStatement(UPSERT_SNAPSHOT_SQL).use { ps ->
            bindSnapshot(ps, snapshot)
            ps.executeUpdate()
        }
    }

    private fun bindSnapshot(ps: java.sql.PreparedStatement, snapshot: ResidenceSnapshot) {
        ps.setString(1, snapshot.nameKey)
        ps.setString(2, snapshot.name)
        ps.setString(3, config.serverId)
        ps.setString(4, snapshot.worldName)
        val teleport = snapshot.teleportLocation
        ps.setString(5, teleport?.worldName)
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
        ps.setString(12, snapshot.ownerName)
        ps.setLong(13, System.currentTimeMillis())
    }

    private fun countByOwner(conn: Connection, ownerUuid: UUID, ownerName: String, includeReserved: Boolean): Int {
        val statusSql = if (includeReserved) "status IN ('ACTIVE','RESERVED')" else "status='ACTIVE'"
        return conn.prepareStatement(
            """
            SELECT COUNT(*) FROM residence_bridge_index
            WHERE $statusSql AND (owner_uuid=? OR (owner_uuid IS NULL AND owner_name=?))
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
        val exists = conn.metaData.getColumns(null, null, table, column).use { it.next() }
        if (!exists) {
            conn.createStatement().use { it.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition") }
        }
    }

    private fun ensureIndex(conn: Connection, table: String, index: String, columns: String) {
        val exists = conn.metaData.getIndexInfo(null, null, table, false, false).use { rs ->
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
            // 脏数据不应让整条查询失败（历史数据可能存的是玩家名而非 UUID）。
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
            expireAt = getLong("expire_at")
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
