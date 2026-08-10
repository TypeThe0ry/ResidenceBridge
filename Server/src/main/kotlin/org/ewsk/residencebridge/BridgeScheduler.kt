package org.ewsk.residencebridge

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

class BridgeTask(private val delegate: Any?, private val cancelAction: (() -> Unit)? = null) {
    fun cancel() {
        try {
            cancelAction?.invoke() ?: delegate?.let { target ->
                val method = cancelMethodCache.computeIfAbsent(target.javaClass) { clazz ->
                    runCatching { clazz.getMethod("cancel") }.getOrNull()
                }
                method?.invoke(target)
            }
        } catch (_: Throwable) {
        }
    }

    private companion object {
        private val cancelMethodCache = ConcurrentHashMap<Class<*>, Method?>()
    }
}

object BridgeScheduler {

    private lateinit var plugin: Plugin
    private var executor: ExecutorService? = null
    private val folia: Boolean by lazy {
        try {
            Bukkit::class.java.getMethod("getGlobalRegionScheduler")
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ===== Folia 反射缓存 =====
    private val foliaGlobalScheduler: Any? by lazy {
        if (!folia) null else Bukkit::class.java.getMethod("getGlobalRegionScheduler").invoke(null)
    }
    private val foliaGlobalRun: Method? by lazy {
        if (!folia) null else foliaGlobalScheduler?.javaClass?.getMethod("run", Plugin::class.java, Consumer::class.java)
    }
    private val foliaGlobalRunDelayed: Method? by lazy {
        if (!folia) null else foliaGlobalScheduler?.javaClass?.getMethod("runDelayed", Plugin::class.java, Consumer::class.java, java.lang.Long.TYPE)
    }
    private val foliaGlobalRunAtFixedRate: Method? by lazy {
        if (!folia) null else foliaGlobalScheduler?.javaClass?.getMethod("runAtFixedRate", Plugin::class.java, Consumer::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE)
    }
    private val foliaPlayerGetScheduler: Method? by lazy {
        if (!folia) null else Player::class.java.getMethod("getScheduler")
    }
    private val foliaPlayerSchedulerClass: Class<*>? by lazy {
        if (!folia) null else foliaPlayerGetScheduler?.returnType
    }
    private val foliaPlayerRun: Method? by lazy {
        if (!folia) null else foliaPlayerSchedulerClass?.getMethod("run", Plugin::class.java, Consumer::class.java, Runnable::class.java)
    }
    private val foliaPlayerRunDelayed: Method? by lazy {
        if (!folia) null else foliaPlayerSchedulerClass?.getMethod("runDelayed", Plugin::class.java, Consumer::class.java, Runnable::class.java, java.lang.Long.TYPE)
    }

    // ===== MethodHandle 缓存（unreflect 一次，后续调用免去 Method.invoke 的可见性检查） =====
    private val foliaGlobalRunHandle: MethodHandle? by lazy { foliaGlobalRun?.let { MethodHandles.lookup().unreflect(it) } }
    private val foliaGlobalRunDelayedHandle: MethodHandle? by lazy { foliaGlobalRunDelayed?.let { MethodHandles.lookup().unreflect(it) } }
    private val foliaGlobalRunAtFixedRateHandle: MethodHandle? by lazy { foliaGlobalRunAtFixedRate?.let { MethodHandles.lookup().unreflect(it) } }
    private val foliaPlayerGetSchedulerHandle: MethodHandle? by lazy { foliaPlayerGetScheduler?.let { MethodHandles.lookup().unreflect(it) } }
    private val foliaPlayerRunHandle: MethodHandle? by lazy { foliaPlayerRun?.let { MethodHandles.lookup().unreflect(it) } }
    private val foliaPlayerRunDelayedHandle: MethodHandle? by lazy { foliaPlayerRunDelayed?.let { MethodHandles.lookup().unreflect(it) } }

    fun init(plugin: Plugin) {
        this.plugin = plugin
        executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "ResidenceBridge-Worker").apply { isDaemon = true }
        }
    }

    /**
     * 优雅关闭：先停止接收新任务，给在途任务有界的完成时间，超时才强制中断。
     * 之前直接 shutdownNow()，会在写库过程中打断线程，留下半完成的批量写入。
     */
    fun shutdown() {
        val current = executor ?: return
        executor = null
        current.shutdown()
        try {
            if (!current.awaitTermination(5, TimeUnit.SECONDS)) {
                current.shutdownNow()
            }
        } catch (_: InterruptedException) {
            current.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    /**
     * 提交异步任务。若线程池已关闭（插件正在停用/重载中），任务会被就地同步执行，
     * 而不是静默丢弃——这条路径上跑的是数据库写入与删除确认，丢掉会造成数据不一致。
     */
    fun runAsync(block: () -> Unit) {
        val current = executor
        if (current == null || current.isShutdown) {
            runCatching { block() }.onFailure { warn("Inline async fallback failed", it) }
            return
        }
        try {
            current.submit {
                try {
                    block()
                } catch (t: Throwable) {
                    warn("Async task failed", t)
                }
            }
        } catch (_: RejectedExecutionException) {
            // 提交与 shutdown 竞态：同样就地执行，保证任务不丢。
            runCatching { block() }.onFailure { warn("Rejected async fallback failed", it) }
        }
    }

    private fun warn(message: String, t: Throwable) {
        if (::plugin.isInitialized) {
            plugin.logger.warning("$message: ${t.message}")
        }
    }

    fun runGlobal(delayTicks: Long = 0L, block: () -> Unit): BridgeTask {
        if (!folia) {
            val task = if (delayTicks <= 0L) {
                Bukkit.getScheduler().runTask(plugin, Runnable(block))
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, Runnable(block), delayTicks)
            }
            return BridgeTask(task) { task.cancel() }
        }
        val consumer = Consumer<Any> { block() }
        val task = if (delayTicks <= 0L) {
            foliaGlobalRunHandle!!.invokeWithArguments(foliaGlobalScheduler, plugin, consumer)
        } else {
            foliaGlobalRunDelayedHandle!!.invokeWithArguments(foliaGlobalScheduler, plugin, consumer, delayTicks)
        }
        return BridgeTask(task)
    }

    fun runPlayer(player: Player, delayTicks: Long = 0L, block: () -> Unit): BridgeTask {
        if (!folia) {
            val task = if (delayTicks <= 0L) {
                Bukkit.getScheduler().runTask(plugin, Runnable(block))
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, Runnable(block), delayTicks)
            }
            return BridgeTask(task) { task.cancel() }
        }
        val scheduler = foliaPlayerGetSchedulerHandle!!.invokeWithArguments(player)
        val consumer = Consumer<Any> { block() }
        val retired = Runnable { }
        val task = if (delayTicks <= 0L) {
            foliaPlayerRunHandle!!.invokeWithArguments(scheduler, plugin, consumer, retired)
        } else {
            foliaPlayerRunDelayedHandle!!.invokeWithArguments(scheduler, plugin, consumer, retired, delayTicks)
        }
        return BridgeTask(task)
    }

    fun runGlobalTimer(initialDelayTicks: Long, periodTicks: Long, block: () -> Unit): BridgeTask {
        if (!folia) {
            val task: BukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(block), initialDelayTicks, periodTicks)
            return BridgeTask(task) { task.cancel() }
        }
        val consumer = Consumer<Any> { block() }
        val task = foliaGlobalRunAtFixedRateHandle!!.invokeWithArguments(
            foliaGlobalScheduler,
            plugin,
            consumer,
            initialDelayTicks.coerceAtLeast(1L),
            periodTicks.coerceAtLeast(1L)
        )
        return BridgeTask(task)
    }
}
