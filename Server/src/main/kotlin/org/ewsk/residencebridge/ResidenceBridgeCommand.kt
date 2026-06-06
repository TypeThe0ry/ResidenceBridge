package org.ewsk.residencebridge

import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandHeader
import taboolib.common.platform.command.PermissionDefault
import taboolib.common.platform.command.subCommand

@CommandHeader(
    name = "rb",
    aliases = ["residencebridge"],
    permission = "residencebridge.command",
    permissionDefault = PermissionDefault.OP
)
object ResidenceBridgeCommand {

    @CommandBody(permission = "residencebridge.command.reload", permissionDefault = PermissionDefault.OP)
    val reload = subCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            try {
                BridgePlugin.reload()
                sender.sendMessage("ResidenceBridge reloaded.")
            } catch (t: Throwable) {
                sender.sendMessage("ResidenceBridge reload failed: ${t.message}")
            }
        }
    }

    @CommandBody(permission = "residencebridge.command.sync", permissionDefault = PermissionDefault.OP)
    val sync = subCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            sender.sendMessage("ResidenceBridge sync started.")
            BridgePlugin.syncNow { count, error ->
                if (error == null) {
                    sender.sendMessage("ResidenceBridge synced $count residences.")
                } else {
                    sender.sendMessage("ResidenceBridge sync failed: ${error.message}")
                }
            }
        }
    }

    @CommandBody(permission = "residencebridge.command.debug", permissionDefault = PermissionDefault.OP)
    val debug = subCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            ResidenceHook.diagnostics().forEach { sender.sendMessage(it) }
        }
    }
}
