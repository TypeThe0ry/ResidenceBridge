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
            BridgePlugin.syncNow { outcome, error ->
                if (error != null || outcome == null) {
                    sender.sendMessage("ResidenceBridge sync failed: ${error?.message}")
                    return@syncNow
                }
                sender.sendMessage("ResidenceBridge synced ${outcome.synced} residences.")
                if (outcome.pruneSkipped) {
                    sender.sendMessage("Warning: no residences were readable, so the existing index rows were kept.")
                }
                if (outcome.skipped > 0) {
                    sender.sendMessage("Warning: ${outcome.skipped} residences were skipped (name longer than 128 characters).")
                }
                if (outcome.conflicts.isNotEmpty()) {
                    sender.sendMessage("Warning: ${outcome.conflicts.size} duplicate name(s) are already registered by another server and were NOT indexed for this server:")
                    outcome.conflicts.take(20).forEach {
                        sender.sendMessage(" - ${it.displayName} (owned by server '${it.otherServerId}', ${it.status})")
                    }
                    if (outcome.conflicts.size > 20) sender.sendMessage(" ... and ${outcome.conflicts.size - 20} more")
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
