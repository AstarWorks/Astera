package com.astarworks.astera.stats.platform

import org.bukkit.plugin.java.JavaPlugin

/**
 * Astera-Stats plugin entrypoint (ADR-0022).
 *
 * Phase 1 placeholder. Phase 4 introduces the Stats / Level / Norma domain
 * (these are NEW types, not migrated from legacy modules). Stage 3-改
 * adds the use cases and the IDomainEventBus listeners that bridge
 * PlayerKilled / DamageDealt / MatchEnded into stat updates.
 */
public class AsteraStatsPlugin : JavaPlugin() {

    override fun onEnable() {
        logger.info("Astera-Stats enabled (Phase 1 skeleton; awaiting Phase 4 migration).")
    }

    override fun onDisable() {
        logger.info("Astera-Stats disabled.")
    }
}
