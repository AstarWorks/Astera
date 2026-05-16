package com.astarworks.astera.economy.platform

import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.economy.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import org.bukkit.plugin.java.JavaPlugin

/**
 * Astera-Economy plugin entrypoint (ADR-0022 Phase 4).
 *
 * Resolves shared services from Astera-Core and publishes an in-memory
 * [ICurrencyLedger]. Use cases (Deposit / Withdraw / Transfer, the
 * `/astera currency …` command tree) land in Stage 3-改. Phase 4 future
 * adds Shop + EnderChest services.
 *
 * The Persistence plugin (Stage 4-改) registers a Postgres-backed ledger
 * at higher priority and `services.getOrThrow<ICurrencyLedger>()` then
 * automatically returns the Postgres version without any wiring change
 * here.
 */
public class AsteraEconomyPlugin : JavaPlugin() {

    private val registrations = mutableListOf<ServiceRegistry.Registration>()

    override fun onEnable() {
        logger.info("Astera-Economy enabling…")

        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Economy.")
                server.pluginManager.disablePlugin(this); return
            }
        val services = core.services

        val ledger: ICurrencyLedger = InMemoryCurrencyLedger()

        registrations += services.register<ICurrencyLedger>(ledger)

        logger.info("Astera-Economy enabled. Services published: ICurrencyLedger")
    }

    override fun onDisable() {
        registrations.forEach { it.unregister() }
        registrations.clear()
        logger.info("Astera-Economy disabled.")
    }
}
