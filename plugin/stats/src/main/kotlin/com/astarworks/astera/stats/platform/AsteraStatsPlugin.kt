package com.astarworks.astera.stats.platform

import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.core.platform.wiring.WiringContext
import com.astarworks.astera.stats.platform.wiring.StatsPlatformPorts
import com.astarworks.astera.stats.platform.wiring.StatsWiring
import com.astarworks.astera.stats.platform.wiring.StatsWiringResult
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
 * Registers `/astera-stats` command tree.
 */
public class AsteraStatsPlugin : JavaPlugin() {

    private var wiringContext: WiringContext? = null
    private var wiringResult: StatsWiringResult? = null

    override fun onEnable() {
        logger.info("Astera-Stats enabling…")

        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Stats.")
                server.pluginManager.disablePlugin(this); return
            }

        // Host-neutral wiring (ADR-0174); Stats has no Paper-only step.
        val ctx = WiringContext(core.services, core.contentDir, logger)
        wiringContext = ctx
        wiringResult = StatsWiring.install(ctx, StatsPlatformPorts())

        logger.info("Astera-Stats enabled.")
    }

    override fun onDisable() {
        wiringResult?.shutdown()
        wiringResult = null
        wiringContext?.close()
        wiringContext = null
        logger.info("Astera-Stats disabled.")
    }
}
