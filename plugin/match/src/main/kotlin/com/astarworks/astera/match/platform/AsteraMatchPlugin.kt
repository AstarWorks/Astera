package com.astarworks.astera.match.platform

import com.astarworks.astera.core.platform.paper.listeners
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.core.application.wiring.WiringOwner
import com.astarworks.astera.core.platform.wiring.WiringContext
import com.astarworks.astera.match.platform.paper.MatchPaperBindings
import com.astarworks.astera.match.platform.wiring.MatchWiring
import com.astarworks.astera.match.platform.wiring.MatchWiringResult
import org.bukkit.plugin.java.JavaPlugin

/**
 * Astera-Match plugin entrypoint (ADR-0022 Phase 4 / Stage 3-改).
 *
 * Resolves shared services from Astera-Core, constructs the four lifecycle use
 * cases, exposes them via IMatchLifecycle on the ServiceRegistry, and
 * registers the `/astera match` command tree.
 *
 * The Persistence plugin (Stage 4-改) registers Postgres-backed impls at
 * higher priority and `services.getOrThrow<IMatchRepository>()` then
 * automatically returns the Postgres version without any wiring change here.
 */
public class AsteraMatchPlugin : JavaPlugin() {

    private var wiringContext: WiringContext? = null
    private var wiringResult: MatchWiringResult? = null

    override fun onEnable() {
        logger.info("Astera-Match enabling…")

        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Match.")
                server.pluginManager.disablePlugin(this); return
            }

        // Host-neutral wiring (ADR-0174), then the Paper-only listener step.
        val ctx = WiringContext(WiringOwner("Astera-Match"), 0, core.services, core.contentDir, logger, core.serverEnvironment.role, core.serverEnvironment.name)
        wiringContext = ctx
        val wired = MatchWiring.install(
            ctx,
            MatchPaperBindings.ports(this, ctx.listeners(this), core.dataFolder.toPath().resolve("replays")),
        )
        wiringResult = wired
        MatchPaperBindings.bind(this, ctx, wired)

        logger.info(
            "Astera-Match enabled. Services: IMatchRepository, IStageRegistry, " +
                "IAnomalyRegistry, IGeneratorRegistry, IMatchLifecycle, IMatchTeamLookup, " +
                "IMatchModeRegistry, IPvpDeathPolicyResolver, " +
                "IMatchRecorder, IMatchReplayRepository, IRecorderStateProvider",
        )
    }

    override fun onDisable() {
        // Replay engine shutdown + scheduler tasks first, then subscriptions /
        // registrations (same order as before ADR-0174).
        wiringResult?.shutdown()
        wiringResult = null
        wiringContext?.close()
        wiringContext = null
        logger.info("Astera-Match disabled.")
    }
}
