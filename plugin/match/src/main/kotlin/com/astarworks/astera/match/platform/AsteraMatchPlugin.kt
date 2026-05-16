package com.astarworks.astera.match.platform

import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.match.application.port.outbound.IMatchRepository
import com.astarworks.astera.match.application.port.outbound.IStageRepository
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.application.service.InMemoryStageRepository
import org.bukkit.plugin.java.JavaPlugin

/**
 * Astera-Match plugin entrypoint (ADR-0022 Phase 4).
 *
 * Resolves shared services from Astera-Core and publishes in-memory
 * [IMatchRepository] / [IStageRepository] impls. Use cases (StartMatch,
 * EndMatch, EntryGame, SwitchPhase, the IMatchLifecycle inbound port,
 * and the `/astera match …` command tree) land in Stage 3-改.
 *
 * The Persistence plugin (Stage 4-改) registers Postgres-backed impls at
 * higher priority and `services.getOrThrow<IMatchRepository>()` then
 * automatically returns the Postgres version without any wiring change
 * here.
 */
public class AsteraMatchPlugin : JavaPlugin() {

    private val registrations = mutableListOf<ServiceRegistry.Registration>()

    override fun onEnable() {
        logger.info("Astera-Match enabling…")

        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Match.")
                server.pluginManager.disablePlugin(this); return
            }
        val services = core.services

        val matches: IMatchRepository = InMemoryMatchRepository()
        val stages: IStageRepository = InMemoryStageRepository()

        registrations += services.register<IMatchRepository>(matches)
        registrations += services.register<IStageRepository>(stages)

        logger.info("Astera-Match enabled. Services published: IMatchRepository, IStageRepository")
    }

    override fun onDisable() {
        registrations.forEach { it.unregister() }
        registrations.clear()
        logger.info("Astera-Match disabled.")
    }
}
