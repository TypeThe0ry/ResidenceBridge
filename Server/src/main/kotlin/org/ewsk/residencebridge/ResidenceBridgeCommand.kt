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
                    sender.sendMessage("你没有权限执行这个操作。")
                    return true
                }
                try {
                    BridgePlugin.reload()
                    sender.sendMessage("ResidenceBridge reloaded.")
                } catch (t: Throwable) {
                    sender.sendMessage("ResidenceBridge reload failed: ${t.message}")
                }
                return true
            }
            "sync" -> {
                if (!sender.hasPermission("residencebridge.command.sync")) {
                    sender.sendMessage("你没有权限执行这个操作。")
                    return true
                }
                sender.sendMessage("ResidenceBridge sync started.")
                BridgePlugin.syncNow { count, error ->
                    if (error == null) {
                        sender.sendMessage("ResidenceBridge synced $count residences.")
                    } else {
                        sender.sendMessage("ResidenceBridge sync failed: ${error.message}")
                    }
                }
                return true
            }
            "debug" -> {
                if (!sender.hasPermission("residencebridge.command.debug")) {
                    sender.sendMessage("你没有权限执行这个操作。")
                    return true
                }
                ResidenceHook.diagnostics().forEach { sender.sendMessage(it) }
                return true
            }
        }
        return false
    }
}
