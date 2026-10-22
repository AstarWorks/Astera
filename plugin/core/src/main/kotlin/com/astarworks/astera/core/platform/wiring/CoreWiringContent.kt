package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.application.port.outbound.IFriendshipRepository
import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPersonalVaultRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.port.outbound.IRewardBridge
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.service.InMemoryAuditLogRepository
import com.astarworks.astera.core.application.service.InMemoryFriendshipRepository
import com.astarworks.astera.core.application.service.InMemoryPartyRepository
import com.astarworks.astera.core.application.service.InMemoryPermissionService
import com.astarworks.astera.core.application.service.InMemoryPersonalVaultRepository
import com.astarworks.astera.core.application.service.InMemoryPlayerAttributeContainer
import com.astarworks.astera.core.application.service.InMemoryPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.service.InMemoryPlayerSessionRepository
import com.astarworks.astera.core.application.service.InMemoryPlayerSettingsRepository
import com.astarworks.astera.core.application.service.InMemoryReloadableContentsRegistry
import com.astarworks.astera.core.application.service.InMemoryRewardBridge
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.application.usecase.permission.AssignRoleUseCase
import com.astarworks.astera.core.application.usecase.permission.CheckPermissionUseCase
import com.astarworks.astera.core.application.usecase.permission.RevokeRoleUseCase
import com.astarworks.astera.core.domain.permission.DefaultRoles
import com.astarworks.astera.core.domain.permission.Permission
import java.nio.file.Path

/** Permission, proxy, reload dispatcher, particle / sound / effect schedulers, player repositories and survival foundation. */
internal fun CoreWiringState.wireContentAndRepositories() {
    // ---- Permission service ------------------------------------------
    // ADR-0195: Astera-Persistence registers its Postgres overlays (priority 100)
    // in its onLoad, i.e. before this wiring runs, so `get` already returns the
    // final implementation. The in-memory one below is the Postgres-less fallback.
    permissionService =
        services.get<IPermissionService>()
            ?: InMemoryPermissionService().let { roles ->
                // Operators / host permission nodes pass too (ADR-0184); roles alone start empty.
                val effective: IPermissionService = ports.hostPermission
                    ?.let { host -> com.astarworks.astera.core.application.service.HostAwarePermissionService(roles, host) }
                    ?: roles
                effective.also { services.register<IPermissionService>(it, priority = 0) }
            }
    logger.info("Permission service registered (in-memory; ${DefaultRoles.builtin().size} default roles).")

    // Wave R 5proxy-impl-wire (ADR-0135): register InMemory proxy backends
    // at priority 0. Same family of silent-drop as Wave Q 5port-fill
    // (ADR-0133 §A) — impl classes shipped by Wave 5r (ADR-0084) but
    // their registration in AsteraCorePlugin was forgotten, meaning live
    // server had no IProxyMessenger / IServerTransferGateway at all and
    // any `services.get<IProxyMessenger>()` returned null. Wave 6b's
    // VelocityProxyMessenger / VelocityServerTransferGateway will overlay
    // at priority 100; the Phase 1 self-loopback InMemory impls provide
    // the baseline so the priority-overlay convention works.
    val remoteMessenger = ports.proxyMessenger?.invoke(ctx)
    val proxyMessenger: com.astarworks.astera.core.application.port.outbound.IProxyMessenger =
        remoteMessenger
            ?: com.astarworks.astera.core.application.service.InMemoryProxyMessenger(localServer = ctx.serverName)
    services.register<com.astarworks.astera.core.application.port.outbound.IProxyMessenger>(
        proxyMessenger,
        priority = if (remoteMessenger != null) 100 else 0,
    )
    // The gateway (permission check, PlayerTransferRequested, deferred queues) is
    // the same over either messenger; only `sendToServer` differs (ADR-0179).
    val serverTransferGateway: com.astarworks.astera.core.application.port.outbound.IServerTransferGateway =
        com.astarworks.astera.core.application.service.InMemoryServerTransferGateway(
            messenger = proxyMessenger,
            eventBus = eventBus,
            permissionService = permissionService,
        )
    services.register<com.astarworks.astera.core.application.port.outbound.IServerTransferGateway>(
        serverTransferGateway,
        priority = if (remoteMessenger != null) 100 else 0,
    )
    logger.info(
        "Proxy messenger registered (${if (remoteMessenger != null) "redis+velocity" else "in-memory"}, " +
            "localServer=${ctx.serverName}, role=${ctx.serverRole}).",
    )

    // Use cases — constructed here for completeness; no Core command uses them yet.
    @Suppress("UNUSED_VARIABLE")
    val assignRole = AssignRoleUseCase(permissionService)
    @Suppress("UNUSED_VARIABLE")
    val checkPermission = CheckPermissionUseCase(permissionService)
    @Suppress("UNUSED_VARIABLE")
    val revokeRole = RevokeRoleUseCase(permissionService)

    // ---- Reload dispatcher (Wave _infra-2b, ADR-0065) ----------------
    // Single Core-side registry of every plugin's ReloadableContents, dispatched
    // via the /astera reload <category> command (Brigadier tab-completed on
    // registered category names). Each feature plugin's onEnable does
    // `services.getOrThrow<IReloadableContentsRegistry>().register(<contents>, <dir>)`
    // so the operator UX is one command for every content type.
    reloadRegistry = InMemoryReloadableContentsRegistry()
    services.register<IReloadableContentsRegistry>(reloadRegistry)

    // Core's own contents (items, gui) register against the dispatcher with
    // their resolved on-disk directories.
    reloadRegistry.register(itemContents, itemsContentDir)
    reloadRegistry.register(guiContents, guiContentDir)
    // Languages swap an immutable key table, so new i18n keys apply without a restart.
    reloadRegistry.register(com.astarworks.astera.core.application.contents.LanguageContents(simpleMessages), contentDir.resolve("languages"))
    // The lobby menu kit layout (ADR-0185); only lobby-hosting backends load and apply it.
    if (ctx.serverRole.hostsLobby) {
        reloadRegistry.register(lobbyMenuKitContents, contentDir)
    }
    // Wave 5x (ADR-0090): custom blocks reload via /astera reload blocks.
    reloadRegistry.register(customBlockContents, customBlocksContentDir)

    // ---- Particle contents (Wave 5f, ADR-0035, RTM #21) -------------
    // ParticleDefinition domain + Contents-driven YAML at content/particles/.
    // Phase 1: domain + registry only — Paper-side spawn (IWorldGateway) is
    // Wave 6b. /astera reload particles routes here via the dispatcher.
    particlesContentDir = contentDir.resolve("particles")
    val particleContents = com.astarworks.astera.core.application.contents.ParticleContents()
    val particleReport = particleContents.bootstrap(particlesContentDir)
    logger.info("Particles loaded: ${particleReport.loaded} ok, ${particleReport.failed} failed")
    services.register<com.astarworks.astera.core.application.port.outbound.IParticleRegistry>(particleContents, priority = 0)
    reloadRegistry.register(particleContents, particlesContentDir)

    // ---- Sprite contents (ADR-0237) ---------------------------------
    // Generated particle sprite metadata at content/sprites/; loaded before VFX so flipbooks know their length.
    val spritesContentDir = contentDir.resolve("sprites")
    val spriteContents = com.astarworks.astera.core.application.contents.SpriteContents()
    val spriteReport = spriteContents.bootstrap(spritesContentDir)
    logger.info("Sprites loaded: ${spriteReport.loaded} ok, ${spriteReport.failed} failed")
    services.register<com.astarworks.astera.core.application.port.outbound.ISpriteRegistry>(spriteContents, priority = 0)
    reloadRegistry.register(spriteContents, spritesContentDir)

    // ---- VFX contents (ADR-0220) ------------------------------------
    // Display choreographies at content/vfx/, played by combat's `play_vfx` mechanic.
    val vfxContentDir = contentDir.resolve("vfx")
    val vfxContents = com.astarworks.astera.core.application.contents.VfxContents()
    val vfxReport = vfxContents.bootstrap(vfxContentDir)
    logger.info("VFX loaded: ${vfxReport.loaded} ok, ${vfxReport.failed} failed")
    services.register<com.astarworks.astera.core.application.port.outbound.IVfxRegistry>(vfxContents, priority = 0)
    reloadRegistry.register(vfxContents, vfxContentDir)

    // ---- Sound contents (Wave 5effects, ADR-0109) -------------------
    // SoundDefinition domain + Contents-driven YAML at content/sounds/.
    // Walks the per-category subdirectories (skills/, combat/, match/, ...)
    // recursively because authors drop one YAML per sound entry.
    val soundsContentDir = contentDir.resolve("sounds")
    val soundContents = com.astarworks.astera.core.application.contents.effect.SoundContents()
    val soundReport = soundContents.bootstrapRecursive(soundsContentDir)
    logger.info("Sounds loaded: ${soundReport.loaded} ok, ${soundReport.failed} failed")
    services.register<com.astarworks.astera.core.application.port.outbound.effect.ISoundRegistry>(soundContents, priority = 0)
    reloadRegistry.register(soundContents, soundsContentDir)

    // ---- Effect schedulers + orchestrator (Wave 5effects, ADR-0109) -
    // The in-memory schedulers compose IScheduler + IWorldGateway. The
    // Paper-side IWorldGateway lands in Wave 6 (currently not in the
    // service registry); when absent we register an inert no-op gateway
    // so the schedulers stay invokable. Cross-plugin appliers
    // (IStatusEffectApplier / IDamageApplier) get no-op defaults at
    // priority 0; combat registers real impls at priority 100.
    val effectsWorldGateway: com.astarworks.astera.core.application.port.outbound.IWorldGateway =
        services.get<com.astarworks.astera.core.application.port.outbound.IWorldGateway>()
            ?: com.astarworks.astera.core.application.service.effect.NoopWorldGatewayForEffects()
    val noopStatusApplier = com.astarworks.astera.core.application.service.effect.NoopStatusEffectApplier()
    val noopDamageApplier = com.astarworks.astera.core.application.service.effect.NoopDamageApplier()
    services.register<com.astarworks.astera.core.application.port.outbound.effect.IStatusEffectApplier>(
        noopStatusApplier, priority = 0,
    )
    services.register<com.astarworks.astera.core.application.port.outbound.effect.IDamageApplier>(
        noopDamageApplier, priority = 0,
    )
    val particleScheduler = com.astarworks.astera.core.application.service.effect.InMemoryParticleScheduler(
        scheduler, effectsWorldGateway,
    )
    val soundScheduler = com.astarworks.astera.core.application.service.effect.InMemorySoundScheduler(
        scheduler, effectsWorldGateway,
    )
    val effectOrchestrator = com.astarworks.astera.core.application.service.effect.InMemoryEffectOrchestrator(
        scheduler, effectsWorldGateway,
        // Resolve through services so combat's higher-priority impls win when registered.
        statusApplier = services.get<com.astarworks.astera.core.application.port.outbound.effect.IStatusEffectApplier>()
            ?: noopStatusApplier,
        damageApplier = services.get<com.astarworks.astera.core.application.port.outbound.effect.IDamageApplier>()
            ?: noopDamageApplier,
    )
    val countdownTimer = com.astarworks.astera.core.application.service.effect.InMemoryCountdownTimer(scheduler)
    val rateLimiter = com.astarworks.astera.core.application.service.effect.InMemoryRateLimiter(scheduler)
    val playerSoundGateway = com.astarworks.astera.core.application.service.effect.LoggingPlayerSoundGateway()
    services.register<com.astarworks.astera.core.application.port.outbound.effect.IParticleScheduler>(
        particleScheduler, priority = 0,
    )
    services.register<com.astarworks.astera.core.application.port.outbound.effect.ISoundScheduler>(
        soundScheduler, priority = 0,
    )
    // ADR-0196: time-animated particle patterns (RTM ParticleScheduler port).
    services.register<com.astarworks.astera.core.application.port.outbound.effect.IParticleAnimator>(
        com.astarworks.astera.core.application.service.effect.SchedulerParticleAnimator(scheduler, effectsWorldGateway),
        priority = 0,
    )
    services.register<com.astarworks.astera.core.application.port.outbound.effect.IEffectOrchestrator>(
        effectOrchestrator, priority = 0,
    )
    services.register<com.astarworks.astera.core.application.port.outbound.effect.ICountdownTimer>(
        countdownTimer, priority = 0,
    )
    services.register<com.astarworks.astera.core.application.port.outbound.effect.IRateLimiter>(
        rateLimiter, priority = 0,
    )
    services.register<com.astarworks.astera.core.application.port.outbound.effect.IPlayerSoundGateway>(
        playerSoundGateway, priority = 0,
    )
    // Wave 6d (ADR-0161): Paper overlay — `Player.playSound(Location, key, MASTER, vol, pitch)`.
    ports.playerSoundGateway?.let {
        services.register<com.astarworks.astera.core.application.port.outbound.effect.IPlayerSoundGateway>(
            it,
            priority = 100,
        )
    }
    logger.info("Wave 5effects (ADR-0109) ports wired: ParticleScheduler / SoundScheduler / EffectOrchestrator / CountdownTimer / RateLimiter / PlayerSoundGateway")

    // ---- Wave 5e Session / Social / Vault / Settings / Reward / Audit / Snapshot / Attribute container ----
    // All in-memory at priority 0; persistence overlays at priority 100 when env vars are set.
    attributeContainerStore =
        services.get<IPlayerAttributeContainerStore>() ?: InMemoryPlayerAttributeContainer().also {
            services.register<IPlayerAttributeContainerStore>(it, priority = 0)
        }
    // Persistence registers its overlays in onLoad (ADR-0197), so `get` already
    // returns the final implementation; the in-memory default is only the fallback.
    sessionRepo = services.get<IPlayerSessionRepository>()
        ?: InMemoryPlayerSessionRepository().also { services.register<IPlayerSessionRepository>(it, priority = 0) }
    if (services.get<IFriendshipRepository>() == null) {
        services.register<IFriendshipRepository>(InMemoryFriendshipRepository(), priority = 0)
    }
    if (services.get<IPartyRepository>() == null) {
        services.register<IPartyRepository>(InMemoryPartyRepository(), priority = 0)
    }
    if (services.get<IPersonalVaultRepository>() == null) {
        services.register<IPersonalVaultRepository>(InMemoryPersonalVaultRepository(), priority = 0)
    }
    settingsRepo = services.get<IPlayerSettingsRepository>()
        ?: InMemoryPlayerSettingsRepository().also { services.register<IPlayerSettingsRepository>(it, priority = 0) }
    if (services.get<IRewardBridge>() == null) {
        services.register<IRewardBridge>(InMemoryRewardBridge(), priority = 0)
    }
    services.register<com.astarworks.astera.core.application.port.outbound.ILastMatchStore>(
        com.astarworks.astera.core.application.service.AttributeLastMatchStore { services.getOrThrow<IPlayerAttributeContainerStore>() },
        priority = 0,
    )
    auditRepo = services.get<IAuditLogRepository>()
        ?: InMemoryAuditLogRepository().also { services.register<IAuditLogRepository>(it, priority = 0) }
    snapshotRepo = services.get<IPlayerInventorySnapshotRepository>()
        ?: InMemoryPlayerInventorySnapshotRepository().also { services.register<IPlayerInventorySnapshotRepository>(it, priority = 0) }

    // Wave 6b-3 (ADR-0158): Paper inventory subsystem — serializer (pure
    // codec) + lifecycle Listener (save on quit / restore on join /
    // save on MatchEnded) + action-lock enforcer (cancel inventory
    // events while PlayerSession.state == Loading). Closes Phase 1 PoC
    // smoke-test item #3 (re-login → loadout 復元).
    ports.inventoryLifecycle?.invoke(services)

    // ---- Wave 5pve-survival (ADR-0107): Skyblock party-shared instance foundation ----
    //
    // Six ports + a YAML-loaded gathering registry. Persistence plugin
    // overlays the three repositories (party island, plot, gathering
    // progress) at priority=100; the manager / persistence / yield-roller
    // / invitation impls stay in-Core (Phase 4 grafts a Postgres island
    // snapshot store on top of IIslandPersistence).
    val partyIslandRepo: com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository =
        services.get<com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository>()
            ?: com.astarworks.astera.core.application.service.survival.InMemoryPartyIslandRepository().also {
                services.register<com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository>(it, priority = 0)
            }
    val islandPlotRepo: com.astarworks.astera.core.application.port.outbound.survival.IIslandPlotRepository =
        services.get<com.astarworks.astera.core.application.port.outbound.survival.IIslandPlotRepository>()
            ?: com.astarworks.astera.core.application.service.survival.InMemoryIslandPlotRepository().also {
                services.register<com.astarworks.astera.core.application.port.outbound.survival.IIslandPlotRepository>(it, priority = 0)
            }
    val gatheringProgressRepo: com.astarworks.astera.core.application.port.outbound.survival.IGatheringProgressRepository =
        services.get<com.astarworks.astera.core.application.port.outbound.survival.IGatheringProgressRepository>()
            ?: com.astarworks.astera.core.application.service.survival.InMemoryGatheringProgressRepository().also {
                services.register<com.astarworks.astera.core.application.port.outbound.survival.IGatheringProgressRepository>(it, priority = 0)
            }
    val partyInvitationRepo = com.astarworks.astera.core.application.service.survival.InMemoryPartyInvitationRepository()
    services.register<com.astarworks.astera.core.application.port.outbound.survival.IPartyInvitationRepository>(partyInvitationRepo, priority = 0)
    val islandTemplateRegistry = com.astarworks.astera.core.application.service.survival.InMemoryIslandTemplateRegistry()
    services.register<com.astarworks.astera.core.application.port.outbound.survival.IIslandTemplateRegistry>(islandTemplateRegistry, priority = 0)
    val islandPersistence: com.astarworks.astera.core.application.port.outbound.survival.IIslandPersistence =
        services.get<com.astarworks.astera.core.application.port.outbound.survival.IIslandPersistence>()
            ?: com.astarworks.astera.core.application.service.survival.InMemoryIslandPersistence(schematicLoader).also {
                services.register<com.astarworks.astera.core.application.port.outbound.survival.IIslandPersistence>(it, priority = 0)
            }
    val inventoryShareGuard: com.astarworks.astera.core.application.port.outbound.survival.IInventoryShareGuard =
        com.astarworks.astera.core.application.service.survival.InMemoryInventoryShareGuard()
    services.register<com.astarworks.astera.core.application.port.outbound.survival.IInventoryShareGuard>(inventoryShareGuard, priority = 0)
    val gatheringYieldRoller: com.astarworks.astera.core.application.port.outbound.survival.IGatheringYieldRoller =
        com.astarworks.astera.core.application.service.survival.UniformGatheringYieldRoller()
    services.register<com.astarworks.astera.core.application.port.outbound.survival.IGatheringYieldRoller>(gatheringYieldRoller, priority = 0)
    // Gathering spot registry — YAML-loaded, joins the reload dispatcher.
    val gatheringContentDir: Path = contentDir.resolve("gathering")
    val gatheringContents = com.astarworks.astera.core.application.contents.survival.GatheringSpotContents()
    val gatheringReport = gatheringContents.bootstrap(gatheringContentDir)
    logger.info("Gathering spots loaded: ${gatheringReport.loaded} ok, ${gatheringReport.failed} failed")
    services.register<com.astarworks.astera.core.application.port.outbound.survival.IGatheringSpotRegistry>(gatheringContents, priority = 0)
    reloadRegistry.register(gatheringContents, gatheringContentDir)
    // Lifecycle use cases + façade managers — wired with the eventBus +
    // schematic loader picked up earlier in onEnable.
    val createPartyIslandUseCase = com.astarworks.astera.core.application.usecase.survival.CreatePartyIslandUseCase(
        partyRepository = services.getOrThrow<IPartyRepository>(),
        islandRepository = partyIslandRepo,
        templateRegistry = islandTemplateRegistry,
        eventBus = eventBus,
    )
    val unloadPartyIslandUseCase = com.astarworks.astera.core.application.usecase.survival.UnloadPartyIslandUseCase(
        islandRepository = partyIslandRepo,
        persistence = islandPersistence,
        eventBus = eventBus,
    )
    val loadPartyIslandUseCase = com.astarworks.astera.core.application.usecase.survival.LoadPartyIslandUseCase(
        islandRepository = partyIslandRepo,
        templateRegistry = islandTemplateRegistry,
        persistence = islandPersistence,
    )
    val visitPartyIslandUseCase = com.astarworks.astera.core.application.usecase.survival.VisitPartyIslandUseCase(
        islandRepository = partyIslandRepo,
        partyRepository = services.getOrThrow<IPartyRepository>(),
    )
    val partyIslandManager = com.astarworks.astera.core.application.service.survival.DefaultPartyIslandManager(
        createUseCase = createPartyIslandUseCase,
        loadUseCase = loadPartyIslandUseCase,
        unloadUseCase = unloadPartyIslandUseCase,
        visitUseCase = visitPartyIslandUseCase,
    )
    services.register<com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandManager>(partyIslandManager, priority = 0)
    val assignPlotUseCase = com.astarworks.astera.core.application.usecase.survival.AssignIslandPlotUseCase(
        islandRepository = partyIslandRepo,
        plotRepository = islandPlotRepo,
        partyRepository = services.getOrThrow<IPartyRepository>(),
        templateRegistry = islandTemplateRegistry,
    )
    val islandPlotManager = com.astarworks.astera.core.application.service.survival.DefaultIslandPlotManager(
        plotRepository = islandPlotRepo,
        assignUseCase = assignPlotUseCase,
    )
    services.register<com.astarworks.astera.core.application.port.outbound.survival.IIslandPlotManager>(islandPlotManager, priority = 0)
}
