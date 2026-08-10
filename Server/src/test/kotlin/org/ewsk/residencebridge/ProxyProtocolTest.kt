package org.ewsk.residencebridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProxyProtocolTest {
    @Test
    fun parsesValidProxyResults() {
        assertEquals(ProxyResult("abc123", "available"), parseProxyResult("result|abc123|available".encodeToByteArray()))
        assertEquals(ProxyResult("req-1", "not-found"), parseProxyResult("RESULT|req-1|NOT-FOUND".encodeToByteArray()))
        assertEquals(ProxyResult("req2", "offline"), parseProxyResult("result|req2|offline".encodeToByteArray()))
        assertEquals(ProxyResult("req3", "connect-failed"), parseProxyResult("result|req3|connect-failed".encodeToByteArray()))
    }

    @Test
    fun rejectsMalformedOrUntrustedProxyResults() {
        listOf(
            "", "result|id", "result||available", "result|bad id|available",
            "result|id|unknown", "other|id|available", "result|id|available|extra"
        ).forEach { assertNull(parseProxyResult(it.encodeToByteArray()), it) }
        assertNull(parseProxyResult(ByteArray(300)))
    }
}
