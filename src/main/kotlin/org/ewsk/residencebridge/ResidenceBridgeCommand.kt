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
                t.printStackTrace()
            }
        }
    }
}
