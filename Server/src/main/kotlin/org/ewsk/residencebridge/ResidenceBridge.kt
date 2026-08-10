package org.ewsk.residencebridge

import org.bukkit.plugin.java.JavaPlugin

class ResidenceBridge : JavaPlugin() {

    override fun onEnable() {
        try {
            BridgePlugin.enable(this)
            logger.info("ResidenceBridge enabled.")
        } catch (t: Throwable) {
            logger.warning("ResidenceBridge failed to enable: ${t.message}")
        }
    }

    override fun onDisable() {
        BridgePlugin.disable()
    }
}
