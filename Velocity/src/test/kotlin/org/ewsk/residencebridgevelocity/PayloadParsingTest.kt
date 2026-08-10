package org.ewsk.residencebridgevelocity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 插件消息载荷解析测试。
 *
 * 这段载荷来自后端服务器，属于不可信输入：解析结果会直接用于查找目标服务器
 * 并对玩家发起连接请求，所以无效输入必须一律归一化为空串（调用方据此丢弃）。
 */
class PayloadParsingTest {

    private fun parse(payload: String) = parseTargetServer(payload.encodeToByteArray())

    @Test
    fun `parses connect prefixed payload`() {
        assertEquals("survival", parse("connect|survival"))
    }

    @Test
    fun `accepts connect prefix case insensitively`() {
        assertEquals("survival", parse("CONNECT|survival"))
        assertEquals("survival", parse("Connect|survival"))
    }

    @Test
    fun `accepts bare server name without prefix`() {
        assertEquals("lobby", parse("lobby"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("survival", parse("  connect|  survival  "))
    }

    @Test
    fun `rejects empty and blank payloads`() {
        assertEquals("", parseTargetServer(ByteArray(0)))
        assertEquals("", parse(""))
        assertEquals("", parse("   "))
        assertEquals("", parse("connect|"))
        assertEquals("", parse("connect|   "))
    }

    @Test
    fun `rejects oversized payload without decoding it`() {
        val huge = "connect|" + "a".repeat(MAX_PAYLOAD_BYTES * 4)
        assertEquals("", parse(huge))
    }

    @Test
    fun `rejects server name longer than the limit`() {
        val longName = "b".repeat(MAX_SERVER_NAME_LENGTH + 1)
        assertEquals("", parse("connect|$longName"))
    }

    @Test
    fun `accepts server name exactly at the limit`() {
        val name = "c".repeat(MAX_SERVER_NAME_LENGTH)
        assertEquals(name, parse("connect|$name"))
    }

    @Test
    fun `rejects names containing whitespace`() {
        // 内嵌空白说明载荷结构不对，不应拿去做服务器查找
        assertEquals("", parse("connect|survival lobby"))
        assertEquals("", parse("connect|survival\tlobby"))
    }

    @Test
    fun `parses status request with request id`() {
        val request = assertIs<ProxyRequest.Status>(parseProxyRequest("status|req123|creative".encodeToByteArray()))
        assertEquals("req123", request.requestId)
        assertEquals("creative", request.targetServer)
    }

    @Test
    fun `parses acknowledged connect request`() {
        val request = assertIs<ProxyRequest.Connect>(parseProxyRequest("connect|req456|survival".encodeToByteArray()))
        assertEquals("req456", request.requestId)
        assertEquals("survival", request.targetServer)
    }

    @Test
    fun `rejects malformed request ids and extra fields`() {
        assertNull(parseProxyRequest("status||creative".encodeToByteArray()))
        assertNull(parseProxyRequest("status|bad id|creative".encodeToByteArray()))
        assertNull(parseProxyRequest("connect|id|survival|extra".encodeToByteArray()))
    }

    @Test
    fun `maps registered and reachable servers to available`() {
        assertEquals("available", proxyStatus(serverRegistered = true, pingSucceeded = true))
    }

    @Test
    fun `maps registered but unreachable servers to offline`() {
        assertEquals("offline", proxyStatus(serverRegistered = true, pingSucceeded = false))
    }

    @Test
    fun `maps unknown servers to not found`() {
        assertEquals("not-found", proxyStatus(serverRegistered = false, pingSucceeded = true))
    }

    @Test
    fun `rejects names containing control characters`() {
        assertEquals("", parse("connect|survival\u0000"))
        assertEquals("", parse("connect|surv\u0007ival"))
    }
}
