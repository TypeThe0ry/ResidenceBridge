package org.ewsk.residencebridge

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import java.util.Locale

object ResidenceBridgeCommand : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.firstOrNull()?.lowercase(Locale.ROOT) ?: return false
        when (sub) {
            "reload" -> {
                if (!sender.hasPermission("residencebridge.command.reload")) {
                    BridgePlugin.send(sender, "command.no-permission")
                    return true
                }
                try {
                    BridgePlugin.reload()
                    BridgePlugin.send(sender, "command.reload-success")
                } catch (t: Throwable) {
                    BridgePlugin.send(sender, "command.reload-failed", mapOf("error" to (t.message ?: t.javaClass.simpleName)))
                }
                return true
            }
            "sync" -> {
                if (!sender.hasPermission("residencebridge.command.sync")) {
                    BridgePlugin.send(sender, "command.no-permission")
                    return true
                }
                BridgePlugin.send(sender, "command.sync-start")
                BridgePlugin.syncNow { count, error ->
                    if (error == null) {
                        BridgePlugin.send(sender, "command.sync-success", mapOf("count" to count))
                    } else {
                        BridgePlugin.send(sender, "command.sync-failed", mapOf("error" to (error.message ?: error.javaClass.simpleName)))
                    }
                }
                return true
            }
            "debug" -> {
                if (!sender.hasPermission("residencebridge.command.debug")) {
                    BridgePlugin.send(sender, "command.no-permission")
                    return true
                }
                // 内存部分在当前（主）线程采集，文件解析放异步线程，避免诊断命令卡服。
                val memoryPart = ResidenceHook.diagnosticsMemoryPart()
                BridgeScheduler.runAsync {
                    val data = ResidenceHook.diagnosticsData(memoryPart)
                    BridgeScheduler.runGlobal {
                        BridgePlugin.send(sender, "debug.plugin", mapOf("value" to data.pluginName))
                        BridgePlugin.send(sender, "debug.instance", mapOf("value" to data.instanceClass))
                        BridgePlugin.send(sender, "debug.manager", mapOf("value" to data.managerClass))
                        BridgePlugin.send(sender, "debug.names", mapOf("count" to data.names.size, "values" to data.names.joinToString()))
                        BridgePlugin.send(sender, "debug.values", mapOf("count" to data.memorySnapshotCount))
                        BridgePlugin.send(sender, "debug.file-snapshots", mapOf("count" to data.fileSnapshotNames.size, "values" to data.fileSnapshotNames.joinToString()))
                        BridgePlugin.send(sender, "debug.snapshots", mapOf("count" to data.mergedSnapshotNames.size, "values" to data.mergedSnapshotNames.joinToString()))
                    }
                }
                return true
            }
        }
        return false
    }
}
