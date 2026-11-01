package com.astarworks.astera.economy.platform

import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.platform.wiring.RewireCoordinator
import com.astarworks.astera.economy.platform.wiring.EconomyLifecycle
import com.astarworks.astera.economy.platform.wiring.EconomyPlatformPorts
import org.bukkit.plugin.java.JavaPlugin

/**
 * Astera-Economy plugin entrypoint (ADR-0022 Phase 4 / Stage 3-改).
 *
 * Resolves shared services from Astera-Core and publishes an in-memory
 * [ICurrencyLedger]. Wires Deposit / Withdraw / Transfer use cases,
 * the match-reward / mission-reward listeners (XP never converts to a currency),
 * and the `/astera economy` command tree.
 *
 * The Persistence plugin (Stage 4-改) registers a Postgres-backed ledger
 * at higher priority and `services.getOrThrow<ICurrencyLedger>()` then
 * automatically returns the Postgres version without any wiring change here.
 */
public class AsteraEconomyPlugin : JavaPlugin() {

    private var coordinator: RewireCoordinator? = null

    override fun onEnable() {
        logger.info("Astera-Economy enabling…")

        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Economy.")
                server.pluginManager.disablePlugin(this); return
            }

        // Host-neutral wiring (ADR-0174) driven through the rewire lifecycle (ADR-0311); Economy has no Paper-only step.
        val rewire = core.rewire
        rewire.register(EconomyLifecycle { EconomyPlatformPorts() })
        when (val up = rewire.up(EconomyLifecycle.OWNER)) {
            is Result.Success -> coordinator = rewire
            is Result.Failure -> {
                logger.severe("Astera-Economy failed to start: ${up.error.message}")
                server.pluginManager.disablePlugin(this); return
            }
        }

        logger.info("Astera-Economy enabled. Services published: ICurrencyLedger, IShopRegistry")
    }

    override fun onDisable() {
        coordinator?.down(EconomyLifecycle.OWNER)
        coordinator = null
        logger.info("Astera-Economy disabled.")
    }
}
