package org.ewsk.residencebridge

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 快照指纹测试。
 *
 * 增量同步靠指纹判断「这条记录要不要写库」：指纹漏掉某个字段，
 * 该字段的变更就会永久同步不出去。这里逐字段验证覆盖情况。
 */
class FingerprintTest {

    private val owner = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun snapshot(
        name: String = "home",
        ownerUuid: UUID? = owner,
        ownerName: String? = "alice",
        worldName: String? = "world",
        teleportLocation: BridgeLocation? = null
    ) = ResidenceSnapshot(name, ownerUuid, ownerName, worldName, teleportLocation)

    @Test
    fun `is stable for identical snapshots`() {
        assertEquals(fingerprint(snapshot()), fingerprint(snapshot()))
    }

    @Test
    fun `detects display name case change with identical name key`() {
        // 回归用例：Home 与 home 的 nameKey 相同，但 display_name 不同，
        // 指纹必须能区分，否则改名结果同步不到数据库。
        val lower = snapshot(name = "home")
        val upper = snapshot(name = "Home")
        assertEquals(lower.nameKey, upper.nameKey)
        assertNotEquals(fingerprint(lower), fingerprint(upper))
    }

    @Test
    fun `detects owner uuid change`() {
        val other = UUID.fromString("00000000-0000-0000-0000-000000000002")
        assertNotEquals(fingerprint(snapshot()), fingerprint(snapshot(ownerUuid = other)))
    }

    @Test
    fun `detects owner uuid removal`() {
        assertNotEquals(fingerprint(snapshot()), fingerprint(snapshot(ownerUuid = null)))
    }

    @Test
    fun `detects owner name change`() {
        assertNotEquals(fingerprint(snapshot()), fingerprint(snapshot(ownerName = "bob")))
    }

    @Test
    fun `detects world change`() {
        assertNotEquals(fingerprint(snapshot()), fingerprint(snapshot(worldName = "nether")))
    }

    @Test
    fun `detects teleport location being added`() {
        val located = snapshot(teleportLocation = BridgeLocation("world", 1.0, 2.0, 3.0))
        assertNotEquals(fingerprint(snapshot()), fingerprint(located))
    }

    @Test
    fun `detects teleport coordinate changes`() {
        val base = snapshot(teleportLocation = BridgeLocation("world", 1.0, 2.0, 3.0))
        val movedX = snapshot(teleportLocation = BridgeLocation("world", 1.5, 2.0, 3.0))
        val movedY = snapshot(teleportLocation = BridgeLocation("world", 1.0, 2.5, 3.0))
        val movedZ = snapshot(teleportLocation = BridgeLocation("world", 1.0, 2.0, 3.5))
        assertNotEquals(fingerprint(base), fingerprint(movedX))
        assertNotEquals(fingerprint(base), fingerprint(movedY))
        assertNotEquals(fingerprint(base), fingerprint(movedZ))
    }

    @Test
    fun `detects teleport rotation changes`() {
        val base = snapshot(teleportLocation = BridgeLocation("world", 1.0, 2.0, 3.0, yaw = 0f, pitch = 0f))
        val yawed = snapshot(teleportLocation = BridgeLocation("world", 1.0, 2.0, 3.0, yaw = 90f, pitch = 0f))
        val pitched = snapshot(teleportLocation = BridgeLocation("world", 1.0, 2.0, 3.0, yaw = 0f, pitch = 45f))
        assertNotEquals(fingerprint(base), fingerprint(yawed))
        assertNotEquals(fingerprint(base), fingerprint(pitched))
    }

    @Test
    fun `detects teleport world change`() {
        val base = snapshot(teleportLocation = BridgeLocation("world", 1.0, 2.0, 3.0))
        val moved = snapshot(teleportLocation = BridgeLocation("nether", 1.0, 2.0, 3.0))
        assertNotEquals(fingerprint(base), fingerprint(moved))
    }
}
