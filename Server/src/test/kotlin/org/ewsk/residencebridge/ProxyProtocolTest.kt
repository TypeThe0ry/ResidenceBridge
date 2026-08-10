package org.ewsk.residencebridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProxyProtocolTest {
    @Test
    fun `parses supported proxy results`() {
        assertEquals(ProxyResult("req-1", "available"), parseProxyResult("result|req-1|available".encodeToByteArray()))
        assertEquals(ProxyResult("req2", "not-found"), parseProxyResult("RESULT|req2|NOT-FOUND".encodeToByteArray()))
        assertEquals(ProxyResult("req3", "connect-failed"), parseProxyResult("result|req3|connect-failed".encodeToByteArray()))
    }

    @Test
    fun `rejects malformed and oversized proxy results`() {
        listOf("", "result|id", "result||available", "result|bad id|available", "result|id|unknown", "result|id|available|extra")
            .forEach { assertNull(parseProxyResult(it.encodeToByteArray()), it) }
        assertNull(parseProxyResult(ByteArray(257)))
    }
}
