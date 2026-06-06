package org.ewsk.residencebridge

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import taboolib.common.platform.function.warning
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
        executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "ResidenceBridge-Worker").apply { isDaemon = true }
        }
    }

    fun shutdown() {
        executor?.shutdownNow()
        executor = null
    }

    fun runAsync(block: () -> Unit) {
        executor?.submit {
            try {
                block()
            } catch (t: Throwable) {
                warning("Async task failed: ${t.message}")
                t.printStackTrace()
            }
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