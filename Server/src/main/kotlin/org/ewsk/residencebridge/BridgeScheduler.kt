package org.ewsk.residencebridge

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import taboolib.common.platform.function.warning
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

class BridgeTask(private val delegate: Any?, private val cancelAction: (() -> Unit)? = null) {
    fun cancel() {
        try {
            cancelAction?.invoke() ?: delegate?.javaClass?.getMethod("cancel")?.invoke(delegate)
        } catch (_: Throwable) {
        }
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

    fun init(plugin: Plugin) {
        this.plugin = plugin
        executor = Executors.newSingleThreadExecutor { runnable ->
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
        val scheduler = Bukkit::class.java.getMethod("getGlobalRegionScheduler").invoke(null)
        val consumer = Consumer<Any> { block() }
        val task = if (delayTicks <= 0L) {
            scheduler.javaClass.getMethod("run", Plugin::class.java, Consumer::class.java).invoke(scheduler, plugin, consumer)
        } else {
            scheduler.javaClass.getMethod("runDelayed", Plugin::class.java, Consumer::class.java, java.lang.Long.TYPE)
                .invoke(scheduler, plugin, consumer, delayTicks)
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
        val scheduler = player.javaClass.getMethod("getScheduler").invoke(player)
        val consumer = Consumer<Any> { block() }
        val retired = Runnable { }
        val task = if (delayTicks <= 0L) {
            scheduler.javaClass.getMethod("run", Plugin::class.java, Consumer::class.java, Runnable::class.java)
                .invoke(scheduler, plugin, consumer, retired)
        } else {
            scheduler.javaClass.getMethod("runDelayed", Plugin::class.java, Consumer::class.java, Runnable::class.java, java.lang.Long.TYPE)
                .invoke(scheduler, plugin, consumer, retired, delayTicks)
        }
        return BridgeTask(task)
    }

    fun runGlobalTimer(initialDelayTicks: Long, periodTicks: Long, block: () -> Unit): BridgeTask {
        if (!folia) {
            val task: BukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(block), initialDelayTicks, periodTicks)
            return BridgeTask(task) { task.cancel() }
        }
        val scheduler = Bukkit::class.java.getMethod("getGlobalRegionScheduler").invoke(null)
        val consumer = Consumer<Any> { block() }
        val task = scheduler.javaClass.getMethod(
            "runAtFixedRate",
            Plugin::class.java,
            Consumer::class.java,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE
        ).invoke(scheduler, plugin, consumer, initialDelayTicks.coerceAtLeast(1L), periodTicks.coerceAtLeast(1L))
        return BridgeTask(task)
    }
}
