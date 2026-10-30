package com.astarworks.astera.stats.platform

import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.platform.wiring.RewireCoordinator
import com.astarworks.astera.stats.platform.wiring.StatsLifecycle
import com.astarworks.astera.stats.platform.wiring.StatsPlatformPorts
import org.bukkit.plugin.java.JavaPlugin

/**
 * Astera-Stats plugin entrypoint (ADR-0022, Stage 3-改).
 *
 * Wires the Stats / Level / Norma domain against Core's shared ports and
 * publishes [IPlayerStatsRepository], [ILevelService], [INormaService] onto the
 * ServiceRegistry so other plugins (e.g. Economy) can resolve them.
 *
 * Subscribes to [PlayerKilled] and [DamageDealt] events from the central
 * IDomainEventBus (soft-dep on Astera-Combat; the plugin still boots without
 * Combat present, subscriptions simply never fire).
 *
 * Registers `/astera stats` command tree.
 */
public class AsteraStatsPlugin : JavaPlugin() {

    private var coordinator: RewireCoordinator? = null

    override fun onEnable() {
        logger.info("Astera-Stats enabling…")

        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Stats.")
                server.pluginManager.disablePlugin(this); return
            }

        // Host-neutral wiring (ADR-0174) driven through the rewire lifecycle (ADR-0311); Stats has no Paper-only step.
        val rewire = core.rewire
        rewire.register(StatsLifecycle { StatsPlatformPorts() })
        when (val up = rewire.up(StatsLifecycle.OWNER)) {
            is Result.Success -> coordinator = rewire
            is Result.Failure -> {
                logger.severe("Astera-Stats failed to start: ${up.error.message}")
                server.pluginManager.disablePlugin(this); return
            }
        }

        logger.info("Astera-Stats enabled.")
    }

    override fun onDisable() {
        coordinator?.down(StatsLifecycle.OWNER)
        coordinator = null
        logger.info("Astera-Stats disabled.")
    }
}
