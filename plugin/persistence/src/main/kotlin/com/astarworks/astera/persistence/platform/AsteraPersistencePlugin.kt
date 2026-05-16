package com.astarworks.astera.persistence.platform

import org.bukkit.plugin.java.JavaPlugin

/**
 * Astera-Persistence plugin entrypoint (ADR-0022).
 *
 * Phase 1 placeholder. Stage 4-改 populates this with Exposed-backed
 * Postgres impls of IMatchRepository / IPlayerStatsRepository /
 * ICurrencyLedger / etc. Registers with higher priority than the
 * in-memory defaults from feature plugins, so callers automatically
 * receive the Postgres impl when this plugin is loaded.
 *
 * Connection configured via env vars: ASTERA_PG_URL / _USER / _PASSWORD.
 * Missing env vars → log a warning and skip registration (each feature
 * plugin continues with its in-memory default).
 */
public class AsteraPersistencePlugin : JavaPlugin() {

    override fun onEnable() {
        logger.info("Astera-Persistence enabled (Phase 1 skeleton; awaiting Stage 4-改 migration).")
    }

    override fun onDisable() {
        logger.info("Astera-Persistence disabled.")
    }
}
