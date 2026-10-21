package com.astarworks.astera.economy.platform

import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.core.platform.wiring.WiringContext
import com.astarworks.astera.economy.platform.wiring.EconomyPlatformPorts
import com.astarworks.astera.economy.platform.wiring.EconomyWiring
import com.astarworks.astera.economy.platform.wiring.EconomyWiringResult
import org.bukkit.plugin.java.JavaPlugin

/**
 * Astera-Economy plugin entrypoint (ADR-0022 Phase 4 / Stage 3-改).
 *
 * Resolves shared services from Astera-Core and publishes an in-memory
 * [ICurrencyLedger]. Wires Deposit / Withdraw / Transfer use cases,
 * the [DepositOnXpListener] (subscribed to [XpGranted] from Stats),
 * and the `/astera-economy` command tree.
 *
 * The Persistence plugin (Stage 4-改) registers a Postgres-backed ledger
 * at higher priority and `services.getOrThrow<ICurrencyLedger>()` then
 * automatically returns the Postgres version without any wiring change here.
 */
public class AsteraEconomyPlugin : JavaPlugin() {

    private var wiringContext: WiringContext? = null
    private var wiringResult: EconomyWiringResult? = null

    override fun onEnable() {
        logger.info("Astera-Economy enabling…")

        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Economy.")
                server.pluginManager.disablePlugin(this); return
            }

        // Host-neutral wiring (ADR-0174); Economy has no Paper-only step.
        val ctx = WiringContext(core.services, core.contentDir, logger)
        wiringContext = ctx
        wiringResult = EconomyWiring.install(ctx, EconomyPlatformPorts())

        logger.info("Astera-Economy enabled. Services published: ICurrencyLedger, IShopRegistry")
    }

    override fun onDisable() {
        wiringResult?.shutdown()
        wiringResult = null
        wiringContext?.close()
        wiringContext = null
        logger.info("Astera-Economy disabled.")
    }
}
