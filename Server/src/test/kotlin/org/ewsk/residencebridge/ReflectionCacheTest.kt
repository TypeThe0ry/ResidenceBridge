package org.ewsk.residencebridge

import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * 反射缓存的空值语义测试。
 *
 * 线上曾出现：Residence 没有 getResidenceManager 方法时，反射查找返回 null，
 * 而 ConcurrentHashMap.getOrPut 会把 null 塞进 putIfAbsent 并抛 NullPointerException。
 * 这个 NPE 从 ResidenceHook 一路冒泡到 confirmCreated，导致创建后的传送点补齐
 * 与「本地不存在则回滚」全部失败，在全局索引里留下查不到实体的幽灵领地。
 *
 * 这里锁定两件事：ConcurrentHashMap 确实不接受 null value（所以不能用 getOrPut），
 * 以及哨兵方案在「存在 / 不存在 / 重复查询」三种情况下的行为都正确。
 */
class ReflectionCacheTest {

    private val absent = Any()

    /** 与 ResidenceHook.cachedOrNull 等价的实现，用于验证缓存契约。 */
    private inline fun <reified T : Any> ConcurrentHashMap<String, Any>.cachedOrNull(
        key: String,
        resolve: () -> T?
    ): T? {
        when (val cached = this[key]) {
            absent -> return null
            is T -> return cached
        }
        val resolved = resolve()
        this[key] = resolved ?: absent
        return resolved
    }

    @Test
    fun `ConcurrentHashMap 不接受 null value`() {
        // 这正是原实现踩到的坑：声明成 ConcurrentHashMap<String, Method?> 后，
        // getOrPut 在解析结果为 null 时会调用 putIfAbsent(key, null) 并抛 NPE。
        // Kotlin 的可空类型在此处给不出保护——擦除后运行时照样把 null 传进去。
        val raw: MutableMap<String, Any?> = ConcurrentHashMap<String, Any?>() as MutableMap<String, Any?>
        assertFailsWith<NullPointerException> {
            raw.getOrPut("missing") { null }
        }
    }

    @Test
    fun `查找不到时返回 null 且不抛异常`() {
        val map = ConcurrentHashMap<String, Any>()
        val result = map.cachedOrNull<String>("missing") { null }
        assertNull(result)
    }

    @Test
    fun `查找不到的结果会被缓存避免重复反射`() {
        val map = ConcurrentHashMap<String, Any>()
        var resolveCount = 0
        repeat(3) {
            map.cachedOrNull<String>("missing") {
                resolveCount++
                null
            }
        }
        // 哨兵命中后不应再次执行 resolve，否则每次调用都要重新走一遍反射查找。
        assertEquals(1, resolveCount, "查找不到的结果没有被缓存")
    }

    @Test
    fun `查找成功时返回同一实例并只解析一次`() {
        val map = ConcurrentHashMap<String, Any>()
        val value = "resolved"
        var resolveCount = 0
        val first = map.cachedOrNull<String>("hit") {
            resolveCount++
            value
        }
        val second = map.cachedOrNull<String>("hit") {
            resolveCount++
            value
        }
        assertSame(value, first)
        assertSame(value, second)
        assertEquals(1, resolveCount, "命中缓存后不应重复解析")
    }

    @Test
    fun `存在与不存在的键互不干扰`() {
        val map = ConcurrentHashMap<String, Any>()
        map.cachedOrNull<String>("missing") { null }
        map.cachedOrNull<String>("hit") { "value" }

        assertNull(map.cachedOrNull<String>("missing") { "should not be used" })
        assertEquals("value", map.cachedOrNull<String>("hit") { "should not be used" })
    }
}
