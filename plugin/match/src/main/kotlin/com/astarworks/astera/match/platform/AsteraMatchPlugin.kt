package com.astarworks.astera.match.platform

import com.astarworks.astera.core.platform.paper.listeners
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.platform.wiring.RewireCoordinator
import com.astarworks.astera.match.platform.paper.MatchPaperBindings
import com.astarworks.astera.match.platform.wiring.MatchWiring
import com.astarworks.astera.match.platform.wiring.MatchLifecycle
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

    private var coordinator: RewireCoordinator? = null

    override fun onEnable() {
        logger.info("Astera-Match enabling…")

        val core = (server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin)
            ?: run {
                logger.severe("Astera-Core not found; disabling Astera-Match.")
                server.pluginManager.disablePlugin(this); return
            }

        // Rewirable generation (ADR-0311): host-neutral wiring (ADR-0174), then the Paper-only listener step.
        coordinator = core.rewire
        core.rewire.register(
            MatchLifecycle(
                install = { ctx ->
                    MatchWiring.install(
                        ctx,
                        MatchPaperBindings.ports(this, ctx.listeners(this), core.dataFolder.toPath().resolve("replays")),
                    )
                },
                bindHost = { ctx, wired -> MatchPaperBindings.bind(this, ctx, wired) },
            ),
        )
        val up = core.rewire.up(MatchLifecycle.OWNER)
        if (up is Result.Failure) {
            logger.severe("Astera-Match failed to wire: ${up.error}")
            server.pluginManager.disablePlugin(this); return
        }

        logger.info(
            "Astera-Match enabled. Services: IMatchRepository, IStageRegistry, " +
                "IAnomalyRegistry, IGeneratorRegistry, IMatchLifecycle, IMatchTeamLookup, " +
                "IMatchModeRegistry, IPvpDeathPolicyResolver, " +
                "IMatchRecorder, IMatchReplayRepository, IRecorderStateProvider",
        )
    }

    override fun onDisable() {
        coordinator?.down(MatchLifecycle.OWNER)
        coordinator = null
        logger.info("Astera-Match disabled.")
    }
}
