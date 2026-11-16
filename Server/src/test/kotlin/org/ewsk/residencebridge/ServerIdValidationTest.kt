package org.ewsk.residencebridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerIdValidationTest {

    @Test
    fun `normalises valid ids`() {
        assertEquals("survival-2", BridgeConfig.validateServerId("  Survival-2 "))
    }

    @Test
    fun `rejects ids the proxy protocol cannot carry`() {
        listOf(null, "", "   ", "a b", "a|b", "x".repeat(65)).forEach { bad ->
            assertFailsWith<IllegalArgumentException>("'$bad' should be rejected") { BridgeConfig.validateServerId(bad) }
        }
    }
}
