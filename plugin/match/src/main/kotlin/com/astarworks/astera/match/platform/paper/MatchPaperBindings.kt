package com.astarworks.astera.match.platform.paper

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.platform.wiring.WiringContext
import com.astarworks.astera.match.adapter.paper.PaperBarrierGateway
import com.astarworks.astera.match.adapter.paper.PaperItemPickupAdapter
import com.astarworks.astera.match.adapter.paper.PaperMatchWorldInputAdapter
import com.astarworks.astera.match.adapter.paper.PaperPillarBlockListener
import com.astarworks.astera.match.adapter.paper.spectator.PaperSpectator
import com.astarworks.astera.match.adapter.paper.spectator.PaperSpectatorInputListener
import com.astarworks.astera.match.platform.wiring.MatchPlatformPorts
import com.astarworks.astera.match.platform.wiring.MatchWiringResult
import org.bukkit.plugin.java.JavaPlugin

/**
 * Paper half of Match's enable (ADR-0174): the [MatchPlatformPorts] the
 * host-neutral `MatchWiring` consumes, plus Bukkit listener registration that
 * runs after wiring. The simulator replaces this with its own ports and
 * publishes `EntityPickedUpItem` directly.
 */
internal object MatchPaperBindings {

    /** [replayExportRoot] is Astera-Core's data folder + `replays` (unchanged from the pre-ADR-0174 path). */
    fun ports(plugin: JavaPlugin, replayExportRoot: java.nio.file.Path): MatchPlatformPorts = MatchPlatformPorts(
        replayExportRoot = replayExportRoot,
        // Wave 6b-1 (ADR-0156): PaperBarrierGateway overlays InMemory at
        // priority 100. Bukkit BlockDisplay-backed visual; Phase 1 limits
        // documented in ADR-0156 §C (visual-only — no server-side collision
        // until Wave 6b-2 WorldGuard region adapter; single-world picks
        // server.worlds.first() until Barrier.worldId field added).
        barrierGateway = { services ->
            PaperBarrierGateway(
                server = plugin.server,
                scheduler = services.getOrThrow<IScheduler>(),
                eventBus = services.getOrThrow<IDomainEventBus>(),
            )
        },
        // F3-5: death cam + eliminated-team spectating use Bukkit spectator mode.
        spectator = { services -> PaperSpectator(plugin.server, services.getOrThrow<IScheduler>()) },
    )

    fun bind(plugin: JavaPlugin, ctx: WiringContext, wired: MatchWiringResult) {
        // Wave 6a-N3 (ADR-0159): Bukkit EntityPickupItemEvent →
        // match.EntityPickedUpItem (PDC astera:item_id read with
        // bukkit:<material> fallback). Paired with the vendor-neutral
        // MatchRegionTransitionListener subscribed in MatchWorldWiring.
        PaperItemPickupAdapter(
            matchRepository = wired.matches,
            eventBus = ctx.services.getOrThrow<IDomainEventBus>(),
            scheduler = ctx.services.getOrThrow<IScheduler>(),
        ).start(plugin)

        // WP11: BlockBreakEvent -> BlockBroken and PlayerMoveEvent -> PlayerMoved for
        // players in a match (objective modes + the region transition listener).
        PaperMatchWorldInputAdapter(
            matches = wired.matches,
            eventBus = ctx.services.getOrThrow<IDomainEventBus>(),
            scheduler = ctx.services.getOrThrow<IScheduler>(),
        ).start(plugin)
        // WP10: enemy block breaks on Annihilation pillar blocks damage the pillar.
        PaperPillarBlockListener(wired.pillarTargets).start(plugin)
        // F3-5: spectators cannot interact / damage / pick up; clicks cycle the camera.
        PaperSpectatorInputListener(wired.spectatorCycler).start(plugin)
    }
}
