package org.ewsk.residencebridge

import taboolib.common.env.RuntimeDependencies
import taboolib.common.env.RuntimeDependency
import taboolib.common.platform.Plugin
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning

@RuntimeDependencies(
    RuntimeDependency(
        value = "!com.zaxxer:HikariCP:4.0.3",
        relocate = [
            "!com.zaxxer.hikari",
            "!org.ewsk.residencebridge.lib.hikari"
        ]
    ),
    RuntimeDependency(
        value = "!com.mysql:mysql-connector-j:8.0.33",
        transitive = false
    )
)
object ResidenceBridge : Plugin() {

    override fun onEnable() {
        try {
            BridgePlugin.enable()
            info("ResidenceBridge enabled.")
        } catch (t: Throwable) {
            warning("ResidenceBridge failed to enable: ${t.message}")
        }
    }

    override fun onDisable() {
        BridgePlugin.disable()
    }
}
