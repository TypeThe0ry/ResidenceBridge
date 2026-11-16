package org.ewsk.residencebridge

import java.sql.DriverManager
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs against a real MySQL/MariaDB. Skipped unless RB_TEST_MYSQL_HOST is set, so plain `gradle test` / CI stay green.
 * Env: RB_TEST_MYSQL_HOST, _PORT, _USER, _PASSWORD, _DB (fresh database), _DB_OTHER (second database on the same server).
 */
class BridgeDatabaseIntegrationTest {

    private val enabled = System.getenv("RB_TEST_MYSQL_HOST") != null
    private val host = System.getenv("RB_TEST_MYSQL_HOST") ?: ""
    private val port = System.getenv("RB_TEST_MYSQL_PORT")?.toIntOrNull() ?: 3306
    private val user = System.getenv("RB_TEST_MYSQL_USER") ?: "root"
    private val password = System.getenv("RB_TEST_MYSQL_PASSWORD") ?: ""
    private val db = System.getenv("RB_TEST_MYSQL_DB") ?: "rbtest"
    private val otherDb = System.getenv("RB_TEST_MYSQL_DB_OTHER") ?: "rbother"
    private val opened = mutableListOf<BridgeDatabase>()

    private fun config(serverId: String, database: String = db) = BridgeConfig(
        serverId = serverId,
        mysql = MysqlConfig(host, port, database, user, password, 4),
        syncInitialDelayTicks = 1, syncIntervalSeconds = 60, syncLogSuccess = false, pendingExpireSeconds = 30,
        joinDelayTicks = 0,
        teleportWait = TeleportWaitConfig(false, 0, "x", false, false, "", 1f, 1f, emptyList()),
        limits = ResidenceLimitConfig(3, "x", emptyList()),
        list = ListConfig(8, "x", "", "", "", ""),
        remoteActionCommands = emptySet(), placeholderCacheSeconds = 30, velocityChannel = "x:y",
        fallbackBungeeChannel = false, serverStatusTimeoutTicks = 60,
        messages = Messages("", "", "", "", "", "", "", "", "", "", "", "", "", "")
    )

    private fun open(serverId: String, database: String = db): BridgeDatabase =
        BridgeDatabase(config(serverId, database)).also { it.initTables(); opened += it }

    private fun sql(database: String, statement: String) {
        DriverManager.getConnection("jdbc:mysql://$host:$port/$database", user, password).use { c ->
            c.createStatement().use { it.executeUpdate(statement) }
        }
    }

    private fun snap(name: String, owner: UUID? = null, ownerName: String? = "Steve") =
        ResidenceSnapshot(name, owner, ownerName, "world")

    @BeforeTest
    fun clean() {
        if (!enabled) return
        listOf(db, otherDb).forEach { database ->
            listOf("residence_bridge_index", "residence_bridge_pending_tp", "residence_bridge_pending_action").forEach {
                runCatching { sql(database, "DROP TABLE IF EXISTS $it") }
            }
        }
    }

    @AfterTest
    fun close() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    @Test
    fun `second server cannot steal an active name and the conflict is reported`() {
        if (!enabled) return
        val a = open("survival-1")
        val b = open("survival-2")
        a.syncServerSnapshots(listOf(snap("Tower", ownerName = "Alice")))

        val outcome = b.syncServerSnapshots(listOf(snap("tower", ownerName = "Bob"), snap("Farm")))

        assertEquals(1, outcome.conflicts.size)
        assertEquals("survival-1", outcome.conflicts[0].otherServerId)
        val entry = assertNotNull(b.findIndex("Tower"))
        assertEquals("survival-1", entry.serverId)
        assertEquals("Alice", entry.ownerName)
        assertEquals("survival-2", b.findIndex("Farm")?.serverId)
    }

    @Test
    fun `single upsert reports conflict`() {
        if (!enabled) return
        val a = open("survival-1")
        val b = open("survival-2")
        a.upsertSnapshot(snap("Spawn"))
        assertNull(a.upsertSnapshot(snap("Spawn")))
        assertEquals("survival-1", b.upsertSnapshot(snap("Spawn"))?.otherServerId)
    }

    @Test
    fun `rename on one server never deletes another servers row`() {
        if (!enabled) return
        val a = open("survival-1")
        val b = open("survival-2")
        a.syncServerSnapshots(listOf(snap("Tower")))
        // b renames a residence it believes is called "Tower" (e.g. a pre-existing duplicate) to "Castle".
        b.replaceRenamed("Tower", snap("Castle"))
        assertEquals("survival-1", a.findIndex("Tower")?.serverId, "server 1's row must survive")
        assertEquals("survival-2", b.findIndex("Castle")?.serverId)
    }

    @Test
    fun `rename on the owning server moves the row`() {
        if (!enabled) return
        val a = open("survival-1")
        a.syncServerSnapshots(listOf(snap("Tower")))
        assertNull(a.replaceRenamed("Tower", snap("Castle")))
        assertNull(a.findIndex("Tower"))
        assertNotNull(a.findIndex("Castle"))
    }

    @Test
    fun `empty sync never prunes the whole server`() {
        if (!enabled) return
        val a = open("survival-1")
        a.syncServerSnapshots(listOf(snap("Tower"), snap("Farm")))
        sql(db, "UPDATE residence_bridge_index SET updated_at=1")   // everything looks ancient
        val outcome = a.syncServerSnapshots(emptyList())
        assertTrue(outcome.pruneSkipped)
        assertNotNull(a.findIndex("Tower"))
        assertNotNull(a.findIndex("Farm"))
    }

    @Test
    fun `non-empty sync prunes only this servers vanished rows`() {
        if (!enabled) return
        val a = open("survival-1")
        val b = open("survival-2")
        a.syncServerSnapshots(listOf(snap("Tower"), snap("Farm")))
        b.syncServerSnapshots(listOf(snap("Mine")))
        sql(db, "UPDATE residence_bridge_index SET updated_at=1")
        val outcome = a.syncServerSnapshots(listOf(snap("Tower")))
        assertEquals(1, outcome.pruned)
        assertNotNull(a.findIndex("Tower"))
        assertNull(a.findIndex("Farm"))
        assertNotNull(a.findIndex("Mine"), "other server's rows are never pruned")
    }

    @Test
    fun `reservation is atomic and respects other servers`() {
        if (!enabled) return
        val a = open("survival-1")
        val b = open("survival-2")
        assertTrue(a.reserveName("Plot", "survival-1", UUID.randomUUID(), "Alice"))
        assertFalse(b.reserveName("PLOT", "survival-2", UUID.randomUUID(), "Bob"))
        // b's sync of an existing same-named residence must not overwrite a's live reservation
        val outcome = b.syncServerSnapshots(listOf(snap("Plot")))
        assertEquals(1, outcome.conflicts.size)
        assertEquals("RESERVED", outcome.conflicts[0].status)
        // a confirms its own reservation
        assertNull(a.upsertSnapshot(snap("Plot")))
        assertEquals("survival-1", a.findIndex("Plot")?.serverId)
    }

    @Test
    fun `create limit counts reservations and rejects duplicates`() {
        if (!enabled) return
        val a = open("survival-1")
        val owner = UUID.randomUUID()
        assertEquals(CreateReservationStatus.RESERVED, a.tryReserveCreate("A1", owner, "Alice", 2).status)
        assertEquals(CreateReservationStatus.DUPLICATE, a.tryReserveCreate("a1", owner, "Alice", 2).status)
        assertEquals(CreateReservationStatus.RESERVED, a.tryReserveCreate("A2", owner, "Alice", 2).status)
        assertEquals(CreateReservationStatus.LIMIT_REACHED, a.tryReserveCreate("A3", owner, "Alice", 2).status)
    }

    @Test
    fun `overlong names are skipped instead of failing the whole sync`() {
        if (!enabled) return
        val a = open("survival-1")
        val outcome = a.syncServerSnapshots(listOf(snap("x".repeat(200)), snap("Ok")))
        assertEquals(1, outcome.skipped)
        assertEquals(1, outcome.synced)
        assertNotNull(a.findIndex("Ok"))
    }

    @Test
    fun `schema check looks only at the current database`() {
        if (!enabled) return
        // A same-named table in ANOTHER database must not make initTables think the columns already exist.
        sql(otherDb, "CREATE TABLE residence_bridge_index (name_key VARCHAR(128) PRIMARY KEY, display_name VARCHAR(128), server_id VARCHAR(64), world VARCHAR(64), owner_uuid VARCHAR(36), owner_name VARCHAR(32), updated_at BIGINT, status VARCHAR(16), tp_world VARCHAR(64), tp_x DOUBLE, tp_y DOUBLE, tp_z DOUBLE, tp_yaw FLOAT, tp_pitch FLOAT)")
        sql(db, "CREATE TABLE residence_bridge_index (name_key VARCHAR(128) PRIMARY KEY, display_name VARCHAR(128) NOT NULL, server_id VARCHAR(64) NOT NULL, world VARCHAR(64), owner_uuid VARCHAR(36), owner_name VARCHAR(32), updated_at BIGINT NOT NULL)")
        val a = open("survival-1")   // old-schema table lacks status/tp_* columns; must be migrated despite `otherDb` having them
        a.syncServerSnapshots(listOf(snap("Tower")))
        assertNotNull(a.findIndex("Tower"))
    }

    @Test
    fun `pending teleport and actions are consumed once`() {
        if (!enabled) return
        val b = open("survival-2")
        val a = open("survival-1")
        val player = UUID.randomUUID()
        val expire = System.currentTimeMillis() + 30_000
        a.writePending(player, "Steve", "Tower", "survival-2", expire)
        a.writePendingAction(player, "Steve", "rename", "res rename Tower Castle", "Tower", "survival-2", expire)
        assertNull(a.consumePending(player), "not addressed to survival-1")
        assertNotNull(b.consumePending(player))
        assertNull(b.consumePending(player))
        assertEquals(1, b.consumePendingActions(player).size)
        assertEquals(0, b.consumePendingActions(player).size)
    }
}
