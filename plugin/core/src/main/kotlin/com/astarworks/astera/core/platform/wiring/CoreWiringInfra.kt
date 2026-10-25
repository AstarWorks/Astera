package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.analytics.NoopAnalyticsExporter
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.service.anticheat.MovementValidatorPacketBridge
import com.astarworks.astera.core.application.port.outbound.packet.IPacketInterceptor
import com.astarworks.astera.core.application.port.outbound.visibility.IPlayerVisibilityGateway
import com.astarworks.astera.core.application.service.packet.InMemoryPacketInterceptor
import com.astarworks.astera.core.application.service.visibility.InMemoryPlayerVisibilityGateway
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.register
import java.nio.file.Path

/** Packet system, analytics, engagement, profile card, cinematic / music and GUI-depth ports. */
internal fun CoreWiringState.wireInfrastructure() {
    // ---- Packet system + per-viewer visibility (Wave 5y, ADR-0090) ----
    // ADR-0015 supersede (PacketEvents section only): the deferred-to-Phase 3+
    // posture is replaced with Phase 1 adoption + a type-erased boundary so
    // core.domain stays packet-library-agnostic. The InMemory implementations
    // ship in Phase 1; the Wave 6c-stub-packetevents Paper adapter swaps
    // them for PacketEvents-backed variants at priority 100 once the
    // soft-dep is confirmed loaded.
    ports.packetInterceptor?.let { create ->
        // Wave 6c-stub-packetevents (ADR-0170): the host factory runs PE lifecycle
        // phase 2 (init) and starts the Netty-backed interceptor; registered at
        // priority 100 so the `services.get<IPacketInterceptor>()` lookup below
        // resolves to it.
        val paperPacketInterceptor = create()
        services.register<IPacketInterceptor>(paperPacketInterceptor, priority = 100)
        logger.info("Wave 6c-stub-packetevents: PaperPacketInterceptor registered at priority 100.")
    }
    val packetInterceptor: IPacketInterceptor =
        services.get<IPacketInterceptor>() ?: InMemoryPacketInterceptor().also {
            services.register<IPacketInterceptor>(it, priority = 0)
        }
    val visibilityGateway: IPlayerVisibilityGateway =
        services.get<IPlayerVisibilityGateway>() ?: InMemoryPlayerVisibilityGateway(eventBus = eventBus).also {
            services.register<IPlayerVisibilityGateway>(it, priority = 0)
        }
    val movementPacketBridge = MovementValidatorPacketBridge(
        movementValidator = movementValidator,
        orchestrator = anticheatOrchestrator,
        tickProvider = { scheduler.currentTick() },
    )
    movementPacketBridgeSub = movementPacketBridge.attach(packetInterceptor)
    logger.info(
        "Wave 5y packet system wired (interceptor=${packetInterceptor::class.simpleName}, " +
            "visibility=${visibilityGateway::class.simpleName}, " +
            "movement bridge -> MovementServerbound).",
    )

    // Observability (Wave _ops, ADR-0023 + ADR-0024): the host decides whether the
    // metrics endpoint is enabled (ASTERA_METRICS_ENABLED) and bootstraps it inside
    // `ports.metrics`; the exporter it returns feeds the analytics listener below.

    // ---- Wave 5u analytics (ADR-0087) + Wave 5engage dashboard (ADR-0104) ----
    // The exporter forwards to the same MeterRegistry the Wave _ops scrape uses
    // when metrics are enabled; otherwise the no-op exporter keeps the listener
    // / dashboard service operational without leaking metrics nowhere visible.
    val analyticsExporter: com.astarworks.astera.core.application.port.outbound.analytics.IAnalyticsExporter =
        ports.metrics?.invoke(services) ?: NoopAnalyticsExporter
    services.register<com.astarworks.astera.core.application.port.outbound.analytics.IAnalyticsExporter>(analyticsExporter, priority = 0)

    val analyticsListener = com.astarworks.astera.core.application.analytics.AnalyticsEventListener(
        eventBus = eventBus,
        exporter = analyticsExporter,
    )
    analyticsListener.start()
    analyticsEventListener = analyticsListener
    logger.info("Wave 5u analytics listener wired (exporter=${analyticsExporter::class.simpleName}).")

    // Wave 5engage dashboard service — InMemory repo at priority 0, Postgres
    // overlay at priority 100 via AsteraPersistencePlugin. The recompute loop
    // ticks every ~5 min (6000 ticks @ 20 TPS); first compute fires 1 min in
    // so initial gauges populate without waiting a full window.
    val engagementRepo: com.astarworks.astera.core.application.port.outbound.engagement.IEngagementSnapshotRepository =
        services.get<com.astarworks.astera.core.application.port.outbound.engagement.IEngagementSnapshotRepository>()
            ?: com.astarworks.astera.core.application.service.engagement.InMemoryEngagementSnapshotRepository().also {
                services.register<com.astarworks.astera.core.application.port.outbound.engagement.IEngagementSnapshotRepository>(it, priority = 0)
            }
    val engagementService = com.astarworks.astera.core.application.service.engagement.EngagementDashboardService(
        repository = engagementRepo,
        exporter = analyticsExporter,
        eventBus = eventBus,
    )
    services.register<com.astarworks.astera.core.application.service.engagement.EngagementDashboardService>(engagementService, priority = 0)
    engagementDashboardHandle = scheduler.runRepeatingTicks(
        initialDelayTicks = 1200L, // 1 min warmup
        periodTicks = 6000L, // 5 min cadence
    ) { engagementService.compute() }
    logger.info("Wave 5engage dashboard service wired (repo=${engagementRepo::class.simpleName}, recompute every 5 min).")

    // ── Wave 5flex (ADR-0102) — Public profile card surface ─────────
    // In-memory default; AsteraPersistencePlugin overlays the Postgres
    // impl at priority=100 via V35.
    val profileCardService: com.astarworks.astera.core.application.port.outbound.IProfileCardService =
        services.get<com.astarworks.astera.core.application.port.outbound.IProfileCardService>()
            ?: com.astarworks.astera.core.application.profile.InMemoryProfileCardService().also {
                services.register<com.astarworks.astera.core.application.port.outbound.IProfileCardService>(it, priority = 0)
            }
    // Recompute use case — display name resolves through IPlayerProfileService.
    // Other providers stay default (empty summaries / zero index); Phase B
    // follow-up waves wire stats / items / economy via feature plugins.
    val profileNameProvider: (com.astarworks.astera.core.domain.player.PlayerId) -> String? = { pid ->
        services.get<com.astarworks.astera.core.application.port.outbound.IPlayerProfileService>()
            ?.find(pid)
            ?.displayName
    }
    // ECO-02 / ECO-04: cosmetics (titles, name colours, kill effects). Catalog from content/cosmetics, owned
    // via the shared unlock store, equipped choice persisted by the Postgres overlay (V70) when loaded.
    val cosmeticsDir: Path = contentDir.resolve("cosmetics")
    val cosmeticContents = com.astarworks.astera.core.application.contents.CosmeticContents()
    val cosmeticReport = cosmeticContents.bootstrap(cosmeticsDir)
    logger.info("Cosmetics loaded: ${cosmeticReport.loaded} ok, ${cosmeticReport.failed} failed")
    services.register<com.astarworks.astera.core.application.port.outbound.cosmetic.ICosmeticCatalog>(cosmeticContents, priority = 0)
    reloadRegistry.register(cosmeticContents, cosmeticsDir)
    val cosmeticLoadouts: com.astarworks.astera.core.application.port.outbound.cosmetic.ICosmeticLoadoutRepository =
        services.get<com.astarworks.astera.core.application.port.outbound.cosmetic.ICosmeticLoadoutRepository>()
            ?: com.astarworks.astera.core.application.service.cosmetic.InMemoryCosmeticLoadoutRepository().also {
                services.register<com.astarworks.astera.core.application.port.outbound.cosmetic.ICosmeticLoadoutRepository>(it, priority = 0)
            }
    val cosmeticService = com.astarworks.astera.core.application.service.cosmetic.CosmeticService(
        catalog = cosmeticContents,
        unlocks = { services.get<com.astarworks.astera.core.application.port.outbound.IPlayerUnlockView>() },
        loadouts = cosmeticLoadouts,
    )
    services.register<com.astarworks.astera.core.application.service.cosmetic.CosmeticService>(cosmeticService, priority = 0)
    val recomputeProfileCard = com.astarworks.astera.core.application.profile.RecomputeProfileCardUseCase(
        cards = profileCardService,
        displayNameProvider = profileNameProvider,
        titlesProvider = cosmeticService::titleSummary,
        cosmeticsProvider = cosmeticService::cosmeticSummary,
    )
    cosmeticService.addChangeListener { recomputeProfileCard.recompute(it) }
    services.register<com.astarworks.astera.core.application.profile.RecomputeProfileCardUseCase>(recomputeProfileCard, priority = 0)
    logger.info("Wave 5flex profile card service wired (impl=${profileCardService::class.simpleName}).")

    // ── Wave 5cinematic (ADR-0110) — Camera + Music gateway foundation ────────
    // Foundation only: ports + in-memory impls + YAML music track registry.
    // Wave 6 lands PaperCameraGateway / PaperCinematicPathPlayer /
    // PaperScreenEffectGateway / PaperMusicGateway over packet primitives
    // at higher priority.
    services.register<com.astarworks.astera.core.application.port.outbound.cinematic.ICameraGateway>(
        com.astarworks.astera.core.application.service.cinematic.InMemoryCameraGateway(eventBus),
        priority = 0,
    )
    // Wave 6a-7 (ADR-0152 §B): PaperCameraGateway overlays InMemory at
    // priority 100. Bukkit `setSpectatorTarget` powers entity-targeted
    // locks + auto-release-on-freeze; pan / shake / zoom / Position-
    // target lock are Phase 1 stubs awaiting Wave 6b packet adapter.
    ports.cameraGateway?.let {
        services.register<com.astarworks.astera.core.application.port.outbound.cinematic.ICameraGateway>(
            it(services),
            priority = 100,
        )
    }
    services.register<com.astarworks.astera.core.application.port.outbound.cinematic.ICinematicPathPlayer>(
        com.astarworks.astera.core.application.service.cinematic.InMemoryCinematicPathPlayer(eventBus),
        priority = 0,
    )
    services.register<com.astarworks.astera.core.application.port.outbound.cinematic.IFreelookGateway>(
        com.astarworks.astera.core.application.service.cinematic.InMemoryFreelookGateway(),
        priority = 0,
    )
    services.register<com.astarworks.astera.core.application.port.outbound.cinematic.IScreenEffectGateway>(
        com.astarworks.astera.core.application.service.cinematic.InMemoryScreenEffectGateway(),
        priority = 0,
    )
    // Music track registry — YAML-loaded, joins the reload dispatcher.
    val musicContentDir: Path = contentDir.resolve("music")
    val musicContents = com.astarworks.astera.core.application.contents.music.MusicTrackContents()
    val musicReport = musicContents.bootstrap(musicContentDir)
    logger.info("Music tracks loaded: ${musicReport.loaded} ok, ${musicReport.failed} failed")
    services.register<com.astarworks.astera.core.application.port.outbound.music.IMusicTrackRegistry>(musicContents, priority = 0)
    reloadRegistry.register(musicContents, musicContentDir)
    services.register<com.astarworks.astera.core.application.port.outbound.music.IMusicGateway>(
        com.astarworks.astera.core.application.service.music.InMemoryMusicGateway(musicContents),
        priority = 0,
    )
    // ADR-0267: Paper overlay plays the track's vanilla sound event on the client's MUSIC bus.
    ports.musicGateway?.let {
        services.register<com.astarworks.astera.core.application.port.outbound.music.IMusicGateway>(
            it(services),
            priority = 100,
        )
    }
    val perMatchBgm = com.astarworks.astera.core.application.service.music.InMemoryPerMatchBgmService(
        tracks = musicContents,
        eventBus = eventBus,
    )
    services.register<com.astarworks.astera.core.application.port.outbound.music.IPerMatchBgmService>(perMatchBgm, priority = 0)
    services.register<com.astarworks.astera.core.application.port.outbound.music.IAdaptiveBgmService>(
        com.astarworks.astera.core.application.service.music.InMemoryAdaptiveBgmService(
            tracks = musicContents,
            perMatch = perMatchBgm,
            eventBus = eventBus,
        ),
        priority = 0,
    )
    logger.info("Wave 5cinematic camera + music gateways wired (tracks loaded=${musicReport.loaded}).")

    // ── Wave 5gui-depth (ADR-0114) — GUI animation + Modal + Tooltip foundation ─
    //
    // 3 priority-0 ports + InMemory defaults. Wave 6 lands:
    //  - PaperGuiAnimationController over EntityMetadata / armor-stand display entities
    //  - PaperGuiModalGateway over anvil-prompt + boss-bar + custom overlay packets
    //  - (Tooltip registry stays in-memory; Paper-side just calls lookup at render time)
    //
    // Tooltip rarity / categories resolvers seeded with `{ null }` / `{ emptyList() }`
    // lambdas — single-line swap when Wave 5refine / Wave 5flex / Wave 5pve-survival
    // wire `IItemRegistry` lookups for ByRarity / ByCategory scope evaluation.
    services.register<com.astarworks.astera.core.application.port.outbound.guidepth.IGuiAnimationController>(
        com.astarworks.astera.core.application.service.guidepth.InMemoryGuiAnimationController(eventBus),
        priority = 0,
    )
    services.register<com.astarworks.astera.core.application.port.outbound.guidepth.IGuiModalGateway>(
        com.astarworks.astera.core.application.service.guidepth.InMemoryGuiModalGateway(eventBus),
        priority = 0,
    )
    services.register<com.astarworks.astera.core.application.port.outbound.guidepth.ITooltipRegistry>(
        com.astarworks.astera.core.application.service.guidepth.InMemoryTooltipRegistry(),
        priority = 0,
    )
    logger.info("Wave 5gui-depth GUI animation + modal + tooltip-extension ports wired.")
}
