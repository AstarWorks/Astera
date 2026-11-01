package com.astarworks.astera.chat.platform

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.core.platform.wiring.RewireCoordinator
import org.bukkit.plugin.java.JavaPlugin

public class AsteraChatPlugin : JavaPlugin() {
    private var coordinator: RewireCoordinator? = null

    override fun onEnable() {
        val core = server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin
            ?: error("Astera-Core is required")
        val rewire = core.rewire
        rewire.register(ChatLifecycle(this, core))
        when (val up = rewire.up(ChatLifecycle.OWNER)) {
            is Result.Success -> coordinator = rewire
            is Result.Failure -> {
                logger.severe("Astera-Chat failed to start: ${up.error.message}")
                server.pluginManager.disablePlugin(this); return
            }
        }
        logger.info("Astera-Chat enabled")
    }

    override fun onDisable() {
        coordinator?.down(ChatLifecycle.OWNER)
        coordinator = null
        server.asyncScheduler.cancelTasks(this)
    }
}
