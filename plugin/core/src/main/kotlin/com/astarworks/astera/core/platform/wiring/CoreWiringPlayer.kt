package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.command.ArgumentType
import com.astarworks.astera.core.application.command.CommandResult
import com.astarworks.astera.core.application.command.CommandSpec
import com.astarworks.astera.core.application.command.requirePermission
import com.astarworks.astera.core.application.contents.MobContents
import com.astarworks.astera.core.application.port.outbound.entity.IEntityRegistry
import com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner
import com.astarworks.astera.core.application.port.outbound.entity.INpcAdapter
import com.astarworks.astera.core.application.port.outbound.entity.INpcInteractionRouter
import com.astarworks.astera.core.application.port.outbound.mob.IMobAdapter
import com.astarworks.astera.core.application.port.outbound.mob.IMobRegistry
import com.astarworks.astera.core.application.port.outbound.mob.IMobSpawnLadder
import com.astarworks.astera.core.application.port.outbound.mob.IMobSpawner
import com.astarworks.astera.core.application.port.outbound.mob.IPveLootDistributor
import com.astarworks.astera.core.application.port.outbound.mob.IPveXpDistributor
import com.astarworks.astera.core.application.port.outbound.IPlayerFoodGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IEgoGauge
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.IResourceRegenScheduler
import com.astarworks.astera.core.application.port.outbound.IPlayerPvpStateService
import com.astarworks.astera.core.application.port.outbound.ISpawnProtectionGateway
import com.astarworks.astera.core.application.port.outbound.ReloadError
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.service.entity.CitizensNpcAdapter
import com.astarworks.astera.core.application.service.entity.InMemoryEntityRegistry
import com.astarworks.astera.core.application.service.entity.InMemoryNpcInteractionRouter
import com.astarworks.astera.core.application.service.entity.MinimalNpcAdapter
import com.astarworks.astera.core.application.service.entity.RouterEntitySpawner
import com.astarworks.astera.core.application.service.mob.MinimalMobAdapter
import com.astarworks.astera.core.application.service.mob.MobSpawnerRouter
import com.astarworks.astera.core.application.service.mob.MythicMobsMobAdapter
import com.astarworks.astera.core.application.service.mob.NoopPveLootDistributor
import com.astarworks.astera.core.application.service.mob.NoopPveXpDistributor
import com.astarworks.astera.core.application.service.mob.StaticMobSpawnLadder
import com.astarworks.astera.core.application.service.DefaultResourceRegenScheduler
import com.astarworks.astera.core.application.service.InMemoryPlayerFoodGateway
import com.astarworks.astera.core.application.service.InMemoryPlayerHealthGateway
import com.astarworks.astera.core.application.service.InMemoryPlayerSpeedGateway
import com.astarworks.astera.core.application.service.InMemoryPlayerPvpStateService
import com.astarworks.astera.core.application.service.EgoConfigLoader
import com.astarworks.astera.core.application.service.InMemoryEgoGauge
import com.astarworks.astera.core.application.service.InMemoryResourcePoolService
import com.astarworks.astera.core.application.service.InMemorySpawnProtectionGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerSpeedGateway
import com.astarworks.astera.core.domain.event.PlayerEnteredMatch
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission

/** Player physiology, resource pools, spawn protection / PvP, /astera reload, entity and mob foundation. */
internal fun CoreWiringState.wirePlayerSystems() {
    // ---- Wave 5k (ADR-0080) Player physiology + Resource pool 3 種 ----
    // Three Bukkit-runtime mutation gateways (health / speed / food) parallel to
    // Wave 5a's IDamageGateway combat pipeline + Wave 5e's IAttributeContainer
    // domain attribute layer. Resource pool service tracks per-weapon Ammo /
    // Stamina / Mana with a tick-driven regen scheduler (Annihilation vision).
    // All InMemory defaults at priority 0; Wave 6a Paper adapters overlay at
    // priority 100 to write through to vanilla `AttributeInstance` /
    // `Player.walkSpeed` / `Player.foodLevel`.
    // Wave 6a-redesign Step 2 (ADR-0150): IPlayerHealthGateway now reads
    // / writes through IAttributeContainer.CURRENT_HP / MAX_HP per
    // ADR-0149 §A. The internal MutableMap that pre-dated Wave 5e's
    // universal-attribute-container migration is gone; the container
    // IS the storage. PaperPlayerHealthGateway (ADR-0151) overlays
    // at priority 100 as UI mirror only.
    playerHealthGateway =
        InMemoryPlayerHealthGateway(attributeContainerStore, scheduler, eventBus)
    services.register<IPlayerHealthGateway>(playerHealthGateway, priority = 0)

    // Wave 6a-redesign Step 3 (ADR-0151, supersedes ADR-0145):
    // PaperPlayerHealthGateway is now a thin wrapper over the
    // container-backed InMemory gateway (Step 2 / ADR-0150). Bukkit
    // player.health is UI mirror only, clamped ≥ MIRROR_MIN_HP=1.0
    // per ADR-0149 §C to prevent vanilla PlayerDeathEvent from ever
    // firing under Astera control.
    ports.playerHealthGateway?.let { services.register<IPlayerHealthGateway>(it(services), priority = 100) }

    val playerSpeedGateway: IPlayerSpeedGateway =
        InMemoryPlayerSpeedGateway(scheduler, eventBus)
    services.register<IPlayerSpeedGateway>(playerSpeedGateway, priority = 0)

    // Wave 6a-4 5paper-sweep-speed (ADR-0147): Paper adapter overlays
    // InMemory at priority 100. Writes back to Player.walkSpeed /
    // flySpeed (baseline-scaled), allowFlight, isSprinting, isCollidable
    // (no-clip approximation per IPlayerSpeedGateway KDoc).
    ports.playerSpeedGateway?.let { services.register<IPlayerSpeedGateway>(it(services), priority = 100) }

    val playerFoodGateway: IPlayerFoodGateway =
        InMemoryPlayerFoodGateway(scheduler, eventBus)
    services.register<IPlayerFoodGateway>(playerFoodGateway, priority = 0)

    // Wave 6a-5 (ADR-0152): Paper food gateway overlays InMemory at
    // priority 100. Writes through to player.foodLevel / player.saturation
    // and registers a Bukkit Listener for FoodLevelChangeEvent so
    // suppressHungerHud(true) cancels vanilla hunger drain (first
    // Paper-adapter-implements-Listener pattern in Astera; see ADR-0152
    // §A for design + sibling-vs-PaperPlayerSpeedGateway divergence).
    ports.playerFoodGateway?.let { services.register<IPlayerFoodGateway>(it(services), priority = 100) }

    // Wave 6a-7 (ADR-0152 §B): movement + vision Paper gateways. No
    // priority-0 InMemory exists for either port (these adapters are
    // the first impl); registered at priority 100 for consistency with
    // the Wave 6a-1..6a-4 pattern.
    ports.playerMovementGateway?.let {
        services.register<com.astarworks.astera.core.application.port.outbound.IPlayerMovementGateway>(it, priority = 100)
    }
    ports.playerVisionGateway?.let {
        services.register<com.astarworks.astera.core.application.port.outbound.IPlayerVisionGateway>(it, priority = 100)
    }

    val resourcePool: IResourcePoolService =
        InMemoryResourcePoolService(scheduler, eventBus, itemContents)
    services.register<IResourcePoolService>(resourcePool, priority = 0)

    val resourceRegen = DefaultResourceRegenScheduler(resourcePool, eventBus)
    services.register<IResourceRegenScheduler>(resourceRegen, priority = 0)
    // Drive the regen pool at server-tick cadence. Per-tick visit walks the
    // tracked player set only — empty cost when no Annihilation match is live.
    val resourceRegenTask = scheduler.runRepeatingTicks(initialDelayTicks = 1L, periodTicks = 1L) {
        resourceRegen.tick(scheduler.currentTick())
    }
    shutdownHooks += { resourceRegenTask.cancel() }
    // Initialise the resource pool whenever a player joins, mirroring the
    // existing Wave 5e RestoreInventorySnapshot subscribe pattern.
    wave5completeSubs += eventBus.subscribe(PlayerJoinedServer::class) { event ->
        resourcePool.initialiseFor(event.playerId)
        resourceRegen.trackPlayer(event.playerId)
    }
    wave5completeSubs += eventBus.subscribe(PlayerQuitServer::class) { event ->
        resourceRegen.untrackPlayer(event.playerId)
    }

    // ADR-0231: the E.G.O gauge. Tuning from content/balance/ego.yaml; emptied on
    // match entry (combat resets it on death); drains once a second out of combat.
    val egoGauge: IEgoGauge =
        InMemoryEgoGauge(scheduler, eventBus, EgoConfigLoader().load(contentDir.resolve("balance/ego.yaml")))
    services.register<IEgoGauge>(egoGauge, priority = 0)
    scheduler.runRepeatingTicks(initialDelayTicks = 20L, periodTicks = 20L) {
        egoGauge.decay(scheduler.currentTick())
    }
    wave5completeSubs += eventBus.subscribe(PlayerEnteredMatch::class) { egoGauge.reset(it.playerId) }
    wave5completeSubs += eventBus.subscribe(PlayerQuitServer::class) { egoGauge.reset(it.playerId) }

    // ---- Wave 5o (ADR-0081) Respawn / Spawn protection / PvP toggle -----
    // ISpawnProtectionGateway: per-player invulnerability window with TTL
    // and PlayerProtectionGranted / PlayerProtectionExpired events.
    // IPlayerPvpStateService: per-player on/off flag + the canonical
    // can-damage resolver consulted by combat-side code before applying
    // damage between two players. Safe-zone predicate is wired to "no
    // safe zones" until Wave 5n region-permission service lands; the
    // service then re-registers the same port at priority 100 with the
    // region overlay attached.
    val spawnProtection: ISpawnProtectionGateway =
        InMemorySpawnProtectionGateway(scheduler, eventBus)
    services.register<ISpawnProtectionGateway>(spawnProtection, priority = 0)

    val pvpStateService: IPlayerPvpStateService =
        InMemoryPlayerPvpStateService(
            scheduler = scheduler,
            eventBus = eventBus,
            protectionGateway = spawnProtection,
            safeZonePredicate = { false }, // Wave 5n region service supplies the real predicate later
        )
    services.register<IPlayerPvpStateService>(pvpStateService, priority = 0)

    // `/astera admin reload <category>` lives in AdminOpsCommands (ADR-0184).

    // ---- Entity spawning + NPC two-tier (Wave 5l, ADR-0082) ----------
    // Two-tier strategy: MinimalNpcAdapter (always available, backs
    // ArmorStandNpc + DisplayNpc) + CitizensNpcAdapter (Wave 5l ships
    // skeleton only; Wave 6b Paper subclass enables full feature set
    // when Citizens is loaded). RouterEntitySpawner picks the highest-
    // priority adapter that supports each SpawnSpec; Citizens-only
    // specs fall through to NoSpawnableBackend without Citizens.
    val entityRegistry: IEntityRegistry = InMemoryEntityRegistry()
    val minimalNpcAdapter = MinimalNpcAdapter()
    val citizensNpcAdapter = CitizensNpcAdapter() // Wave 5l skeleton (isAvailable=false)
    val npcInteractionRouter: INpcInteractionRouter = InMemoryNpcInteractionRouter()
    val entitySpawner: IEntitySpawner = RouterEntitySpawner(
        // Citizens listed first so the router tries it before Minimal
        // (skeleton's supports()=false so it cleanly falls through).
        npcAdapters = listOf(citizensNpcAdapter, minimalNpcAdapter),
        eventBus = eventBus,
        scheduler = scheduler,
        registry = entityRegistry,
        interactionRouter = npcInteractionRouter,
    )
    services.register<IEntityRegistry>(entityRegistry, priority = 0)
    services.register<INpcAdapter>(minimalNpcAdapter, priority = 0)
    services.register<INpcAdapter>(citizensNpcAdapter, priority = 10) // edge tier ranks above minimal
    services.register<INpcInteractionRouter>(npcInteractionRouter, priority = 0)
    services.register<IEntitySpawner>(entitySpawner, priority = 0)

    // ---- PvE mob foundation (Wave 5pve, ADR-0085) --------------------
    // Two-tier strategy: MinimalMobAdapter (always available) +
    // MythicMobsMobAdapter (Phase 1 skeleton, Wave 6b activates with
    // MythicMobs plugin detection). MobSpawnerRouter picks the
    // highest-priority *available* adapter; MythicMobs listed first so
    // Wave 6b's enabled subclass takes precedence without re-ordering.
    //
    // Pve loot + xp distributors are Noop in Phase 1 — Phase 4 supersedes
    // via priority-100 service registration once content/loot/ + the XP
    // yield table land.
    val mobsContentDir = contentDir.resolve("mobs")
    val mobContents = MobContents()
    val mobReport = mobContents.bootstrap(mobsContentDir)
    logger.info("Mobs loaded: ${mobReport.loaded} ok, ${mobReport.failed} failed")
    services.register<IMobRegistry>(mobContents, priority = 0)
    reloadRegistry.register(mobContents, mobsContentDir)

    val minimalMobAdapter = MinimalMobAdapter(eventBus, scheduler)
    val mythicMobsMobAdapter = MythicMobsMobAdapter()
    services.register<IMobAdapter>(mythicMobsMobAdapter, priority = 10)
    services.register<IMobAdapter>(minimalMobAdapter, priority = 0)
    mobSpawner = MobSpawnerRouter(
        adapters = listOf(mythicMobsMobAdapter, minimalMobAdapter),
        registry = mobContents,
        eventBus = eventBus,
        scheduler = scheduler,
    )
    services.register<IMobSpawner>(mobSpawner, priority = 0)
    services.register<IMobSpawnLadder>(StaticMobSpawnLadder(), priority = 0)
    services.register<IPveLootDistributor>(NoopPveLootDistributor(), priority = 0)
    services.register<IPveXpDistributor>(NoopPveXpDistributor(), priority = 0)
    logger.info(
        "Wave 5pve mob foundation wired (active backend=${mobSpawner.activeBackendId()?.value ?: "none"}).",
    )
}
