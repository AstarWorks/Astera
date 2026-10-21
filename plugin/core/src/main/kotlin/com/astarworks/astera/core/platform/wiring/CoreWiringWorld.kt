package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.adapter.api.hud.IBossBarManager
import com.astarworks.astera.core.adapter.api.hud.IScoreboardManager
import com.astarworks.astera.core.adapter.api.hud.ITabList
import com.astarworks.astera.core.application.contents.block.CustomBlockContents
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.port.outbound.ISchematicLoader
import com.astarworks.astera.core.application.port.outbound.IMatchConfigService
import com.astarworks.astera.core.application.port.outbound.IWorldRulesGateway
import com.astarworks.astera.core.application.port.outbound.IWorldTimeGateway
import com.astarworks.astera.core.application.port.outbound.IWorldWeatherGateway
import com.astarworks.astera.core.application.service.BlockDurabilityLoader
import com.astarworks.astera.core.application.service.InMemoryBlockDurabilityRepository
import com.astarworks.astera.core.application.service.InMemoryBlockStateGateway
import com.astarworks.astera.core.application.service.InMemoryBossBarManager
import com.astarworks.astera.core.application.service.InMemoryScoreboardManager
import com.astarworks.astera.core.application.service.InMemorySchematicLoader
import com.astarworks.astera.core.application.service.InMemoryTabList
import com.astarworks.astera.core.application.port.outbound.IRegionPermissionService
import com.astarworks.astera.core.application.port.outbound.IStageRegionIndex
import com.astarworks.astera.core.application.port.outbound.IPlayerLocationQueryService
import com.astarworks.astera.core.application.service.world.InMemoryWorldTimeGateway
import com.astarworks.astera.core.application.service.world.InMemoryWorldWeatherGateway
import com.astarworks.astera.core.application.service.world.InMemoryWorldRulesGateway
import com.astarworks.astera.core.application.service.region.InMemoryRegionPermissionService
import com.astarworks.astera.core.application.service.spatial.InMemoryStageRegionIndex
import com.astarworks.astera.core.application.service.spatial.InMemoryPlayerLocationQueryService
import com.astarworks.astera.core.application.port.outbound.IBlockDurabilityRepository
import com.astarworks.astera.core.application.service.match.InMemoryMatchConfigService
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.world.BlockDurabilityRule

/** HUD ports, world geometry, region / spatial index and per-match config. */
internal fun CoreWiringState.wireHudAndWorld() {
    // ---- HUD ports (Wave 5b, ADR-0031) -------------------------------
    // In-memory defaults at priority 0. Wave 6 Paper impls land at
    // priority 100 (Adventure-backed BossBar / Scoreboard / TabList).
    val bossBarManager: IBossBarManager = InMemoryBossBarManager()
    val scoreboardManager: IScoreboardManager = InMemoryScoreboardManager()
    tabList = InMemoryTabList()
    services.register<IBossBarManager>(bossBarManager, priority = 0)
    services.register<IScoreboardManager>(scoreboardManager, priority = 0)
    services.register<ITabList>(tabList, priority = 0)

    // Wave 6b-2 (ADR-0157): Paper HUD adapters overlay InMemory at
    // priority 100. Adventure API (Paper-bundled) is the rendering
    // substrate. PaperHudGateway is wired later (after messages
    // resolution) since it depends on PaperBossBarManager + eventBus.
    ports.bossBarManager?.let { services.register<IBossBarManager>(it, priority = 100) }
    // Wave 6c-stub-packetevents-B/C (ADR-0171 / ADR-0172): per-viewer scoreboard and
    // tab-list locale need PacketEvents senders; the host factory builds them
    // (strictly behind its own packetEventsInstalled gate) from the message renderer.
    ports.scoreboardManager?.let { services.register<IScoreboardManager>(it(services), priority = 100) }
    ports.tabList?.let { services.register<ITabList>(it(services), priority = 100) }

    // ---- World geometry ports (Wave 5d, ADR-0033) --------------------
    // BlockDurabilityRule loaded from content/balance/block-durability.yaml
    // with fall-soft to defaultMatrix() on missing/malformed file.
    // InMemoryBlockStateGateway + InMemorySchematicLoader registered at
    // priority 0; Wave 6 Paper impls land at priority 100
    // (BlockData + NBT durability / FAWE adapter).
    val durabilityRule = BlockDurabilityLoader().load(
        contentDir.resolve("balance/block-durability.yaml"),
    )
    // Wave 5x (ADR-0090): Custom-block registry, loaded from
    // content/blocks/<id>.yaml. The InMemoryBlockStateGateway holds a
    // reference so applyDamageFromSkill can resolve per-block tool
    // effectiveness + drops without an extra registry lookup at every
    // hit. Hot-reload via /astera reload blocks.
    customBlocksContentDir = contentDir.resolve("blocks")
    customBlockContents = CustomBlockContents()
    val customBlockReport = customBlockContents.bootstrap(customBlocksContentDir)
    logger.info(
        "Custom blocks loaded: ${customBlockReport.loaded} ok, ${customBlockReport.failed} failed",
    )
    services.register<ICustomBlockRegistry>(customBlockContents, priority = 0)

    // Wave 5x (ADR-0090): Postgres-backed block durability repository
    // (in-memory default at priority 0; Persistence overlays at 100).
    val blockDurabilityRepository: IBlockDurabilityRepository =
        services.get<IBlockDurabilityRepository>() ?: InMemoryBlockDurabilityRepository().also {
            services.register<IBlockDurabilityRepository>(it, priority = 0)
        }
    @Suppress("UNUSED_VARIABLE") val blockDurabilityRepositoryRef = blockDurabilityRepository

    // Wave 6c (ADR-0160): write-through block change journal
    // (in-memory default at priority 0; Persistence overlays Postgres at
    // 100 when ASTERA_PG_URL is set). Hot path: PaperBlockStateGateway
    // calls save()/delete() per mutation; loadAll() drives warm-up at
    // plugin enable.
    val blockChangeJournal: com.astarworks.astera.core.application.port.outbound.IBlockChangeJournalRepository =
        services.get<com.astarworks.astera.core.application.port.outbound.IBlockChangeJournalRepository>()
            ?: com.astarworks.astera.core.application.service.InMemoryBlockChangeJournalRepository().also {
                services.register<com.astarworks.astera.core.application.port.outbound.IBlockChangeJournalRepository>(
                    it,
                    priority = 0,
                )
            }
    @Suppress("UNUSED_VARIABLE") val blockChangeJournalRef = blockChangeJournal

    val blockStateGateway: IBlockStateGateway = InMemoryBlockStateGateway(
        eventBus = eventBus,
        customBlockRegistry = customBlockContents,
        durabilityRule = durabilityRule,
    )
    schematicLoader = InMemorySchematicLoader()
    services.register<IBlockStateGateway>(blockStateGateway, priority = 0)
    services.register<ISchematicLoader>(schematicLoader, priority = 0)
    // Wave 6a-N3 (ADR-0159): InMemoryStructurePaste — Phase 1 stub for
    // IStructurePaste. Wave 6b+ FAWE adapter (`PaperStructurePaste`)
    // will overlay at priority 100; until then this in-memory delta
    // capture satisfies anomaly-spawned-structure use cases
    // (paste-then-undo round trip with no real world I/O).
    services.register<com.astarworks.astera.core.application.port.outbound.IStructurePaste>(
        com.astarworks.astera.core.application.service.InMemoryStructurePaste(),
        priority = 0,
    )

    // Wave 6b-1 (ADR-0156): Paper world + block adapters overlay InMemory
    // at priority 100. Bukkit-backed everywhere with Phase-1 stubs documented
    // in ADR-0156 §A (PaperWorldGateway.spectatorSpawn falls back to
    // lobbySpawn; PaperBlockStateGateway uses in-memory durability for
    // non-TileState blocks until Wave 6b-2 Postgres journal lands).
    ports.worldGateway?.let {
        services.register<com.astarworks.astera.core.application.port.outbound.IWorldGateway>(
            it(services),
            priority = 100,
        )
    }
    // Wave 6c §B (ADR-0160) / Wave 6d-stub-warmup (ADR-0166): the host factory also
    // rehydrates the gateway's tracker from the block change journal for every
    // world loaded at enable.
    ports.blockStateGateway?.let { factory ->
        services.register<IBlockStateGateway>(
            factory(BlockStateGatewayDeps(services, customBlockContents, durabilityRule)),
            priority = 100,
        )
    }
    // BlockDurabilityRule is consumed by use cases (e.g. Wave 6 BlockHitListener
    // computing damageFor(tool, blockType) before calling applyDamage). Published
    // here so future plugins resolve through the registry rather than re-loading.
    services.register<BlockDurabilityRule>(durabilityRule, priority = 0)

    // ---- World environment + Per-match config + Spatial index (Wave 5n, ADR-0080) ----
    // In-memory defaults at priority 0. Wave 6b Paper adapter overlays at
    // priority 100 (World.setTime / setStorm / setGameRule / setPVP). The
    // spatial-index + region-permission ports stay in-memory in PoC; the
    // Postgres `region_permission` overlay (V25) is wired by Astera-Persistence
    // at priority 100 if the env vars are set.
    val worldTimeGateway: IWorldTimeGateway = InMemoryWorldTimeGateway()
    val worldWeatherGateway: IWorldWeatherGateway = InMemoryWorldWeatherGateway()
    val worldRulesGateway: IWorldRulesGateway = InMemoryWorldRulesGateway()
    services.register<IWorldTimeGateway>(worldTimeGateway, priority = 0)
    services.register<IWorldWeatherGateway>(worldWeatherGateway, priority = 0)
    services.register<IWorldRulesGateway>(worldRulesGateway, priority = 0)

    // Wave 6d (ADR-0161): Paper overlays at priority 100. Live `World.setTime`
    // / `setStorm` / `setGameRule` / `setDifficulty` / `setPVP` against
    // `Bukkit.getServer().getWorld(worldId)`. InMemory @ p=0 still satisfies
    // the port when running outside Paper (tests / ScenarioDsl).
    ports.worldTimeGateway?.let { services.register<IWorldTimeGateway>(it, priority = 100) }
    ports.worldWeatherGateway?.let { services.register<IWorldWeatherGateway>(it, priority = 100) }
    ports.worldRulesGateway?.let { services.register<IWorldRulesGateway>(it, priority = 100) }

    stageRegionIndex = InMemoryStageRegionIndex()
    services.register<IStageRegionIndex>(stageRegionIndex, priority = 0)

    // Region permission: the in-memory default consults the in-memory
    // stage-region index. Persistence overlays this at priority 100 via
    // PostgresRegionPermissionService(db, services.getOrThrow<IStageRegionIndex>()).
    val regionPermissionService: IRegionPermissionService =
        services.get<IRegionPermissionService>()
            ?: InMemoryRegionPermissionService(stageRegionIndex).also {
                services.register<IRegionPermissionService>(it, priority = 0)
            }
    @Suppress("UNUSED_VARIABLE") val regionPermissionRef = regionPermissionService

    val playerLocationQueryService: IPlayerLocationQueryService =
        InMemoryPlayerLocationQueryService()
    services.register<IPlayerLocationQueryService>(playerLocationQueryService, priority = 0)

    // Per-match config service (Wave 5c bridge): apply/reset overlay at
    // match start/end. Uses the just-registered world gateways.
    val matchConfigService: IMatchConfigService = InMemoryMatchConfigService(
        worldRules = worldRulesGateway,
        worldTime = worldTimeGateway,
        worldWeather = worldWeatherGateway,
        eventBus = eventBus,
    )
    services.register<IMatchConfigService>(matchConfigService, priority = 0)
}
