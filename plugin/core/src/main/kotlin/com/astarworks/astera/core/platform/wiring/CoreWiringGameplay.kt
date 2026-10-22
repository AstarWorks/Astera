package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register

/** Player animation, hologram transitions and the Wave 5wireup E2E gameplay loop. */
internal fun CoreWiringState.wireGameplayLoop() {
    // ── Wave 5animation (ADR-0113) — Player animation gateway ──
    //
    // The InMemory default was shipped but never registered (Wave 5animation
    // pre-empted Wave 6 — Paper impl was deferred). Wave 6d (ADR-0161)
    // closes the loop: register the InMemory at p=0 + PaperPlayerAnimationGateway
    // at p=100. `tickClock = scheduler::currentTick` so published animation
    // events carry real server ticks.
    services.register<com.astarworks.astera.core.application.port.outbound.animation.IPlayerAnimationGateway>(
        com.astarworks.astera.core.application.service.animation.InMemoryPlayerAnimationGateway(
            eventBus = eventBus,
            tickClock = scheduler::currentTick,
        ),
        priority = 0,
    )
    ports.playerAnimationGateway?.let {
        services.register<com.astarworks.astera.core.application.port.outbound.animation.IPlayerAnimationGateway>(
            it(services),
            priority = 100,
        )
    }
    logger.info("Wave 5animation (ADR-0113) PlayerAnimation port wired (InMemory @ p=0, Paper @ p=100 via ADR-0161).")

    // ── Wave 5transitions (ADR-0115) — DisplayEntity transition foundation ─
    //
    // Wave 5h shipped `IHologramGateway` as a write-only surface but did
    // not register an InMemory default — feature plugins that wanted a
    // hologram couldn't get one in tests. Wave 5transitions both wires
    // the gateway (so PoC tooling has one out of the box) and lands its
    // transition controller (so Wave 5h's reactive binding pipeline can
    // tween per-field, including the 16 new per-type updaters).
    //
    // The controller subscribes to its own repeating tick task at
    // construction; the explicit `holoTransitionController` reference is
    // retained so [onDisable] can call `shutdown()` which cancels the
    // tick task AND publishes `HologramTransitionCancelled(reasonKind =
    // "scheduler_shutdown")` for every in-flight transition (no silent
    // leak on plugin reload).
    services.register<com.astarworks.astera.core.application.port.outbound.IHologramGateway>(
        com.astarworks.astera.core.application.service.InMemoryHologramGateway(),
        priority = 0,
    )
    // Wave 6f (ADR-0163): Paper overlay routes through Bukkit Display
    // Entity API (TextDisplay / ItemDisplay / BlockDisplay). 28 methods
    // per ADR-0115; shadow-map for snapshot read-back; main-thread guard
    // with off-main writes returning Storage("off-main: <op>") (callers
    // should be driven by the transition controller which runs main-thread).
    // Registered BEFORE `getOrThrow<IHologramGateway>()` so the
    // transition controller below tweens against the live Paper impl.
    ports.hologramGateway?.let {
        services.register<com.astarworks.astera.core.application.port.outbound.IHologramGateway>(
            it(services),
            priority = 100,
        )
    }
    // Wave 6g-visibility (ADR-0168): the host factory also binds the plugin for
    // Player.showEntity / hideEntity and registers the JoinReapplyListener.
    val holoGateway = services
        .getOrThrow<com.astarworks.astera.core.application.port.outbound.IHologramGateway>()
    val holoTransitionController =
        com.astarworks.astera.core.application.service.hologram.InMemoryHologramTransitionController(
            gateway = holoGateway,
            scheduler = scheduler,
            eventBus = eventBus,
        )
    services.register<com.astarworks.astera.core.application.port.outbound.hologram.IHologramTransitionController>(
        holoTransitionController,
        priority = 0,
    )
    this.holoTransitionController = holoTransitionController
    logger.info("Wave 5transitions hologram gateway + transition controller wired.")

    // ── Wave 5wireup (ADR-0116) — E2E gameplay loop wireup ────────────
    //
    // Foundations that prior waves shipped but never wired:
    //   - InMemoryPlayerProfileService (5e-fix)
    //   - InMemoryDialogGateway (5i)
    //   - InMemoryHudGateway (5j)
    // Listeners that prior waves authored but never subscribed:
    //   - CreateFirstTimeProfileOnPlayerJoinedListener (5e-fix, G1)
    //   - StartTutorialOnFirstTimeProfileCreatedListener (G2)
    //   - ShowWelcomeDialogOnFirstTimeProfileCreatedListener (G3)
    //   - HudUpdateOnCurrencyChangedListener (G6)
    // Use cases + commands that did not exist before this wave:
    //   - TravelToPveWorldUseCase / TravelToSurvivalIslandUseCase /
    //     ReturnToLobbyUseCase (G13/G14/G15)
    //   - /astera travel / /astera menu / /astera help (G10/G11/G12)
    // Plus QueueCommandSpec (5w) which `AsteraMatchPlugin` defines but
    // never registers — wired alongside the lifecycle facade lookup on
    // the match plugin side.
    //
    // Wave 6 + Postgres overlay swaps the InMemory defaults later (same
    // pattern as every other Astera port).

    // 1. Foundation: InMemory gateways the listeners depend on.
    val dialogGateway: com.astarworks.astera.core.application.port.outbound.IDialogGateway =
        services.get<com.astarworks.astera.core.application.port.outbound.IDialogGateway>()
            ?: com.astarworks.astera.core.application.service.InMemoryDialogGateway(eventBus).also {
                services.register<com.astarworks.astera.core.application.port.outbound.IDialogGateway>(it, priority = 0)
            }
    // Wave 6b-2 (ADR-0157): PaperHudGateway overlays InMemoryHudGateway
    // at priority 100. Routes ActionBar / StatusLine / Subtitle through
    // Adventure API (Player.sendActionBar / showTitle); Sidebar / TabList
    // layouts route through their dedicated managers wired earlier.
    // There is no boss-bar surface (F2-2, ADR-0194).
    services.get<com.astarworks.astera.core.application.port.outbound.IHudGateway>()
        ?: com.astarworks.astera.core.application.service.InMemoryHudGateway(eventBus, scheduler).also {
            services.register<com.astarworks.astera.core.application.port.outbound.IHudGateway>(it, priority = 0)
        }
    ports.hudGateway?.let {
        services.register<com.astarworks.astera.core.application.port.outbound.IHudGateway>(
            it(services),
            priority = 100,
        )
    }
    hudGateway =
        services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IHudGateway>()

    // 2. Player profile service. The starter grant is read from
    //    `content/balance/starter-grant.yaml` if present; otherwise the
    //    listener still works with an empty grant (no loadout / wallet
    //    credit / tutorial / dialog) and the join completes silently.
    val starterGrantPath = contentDir.resolve("balance").resolve("starter-grant.yaml")
    val starterGrant = com.astarworks.astera.core.application.service
        .StarterGrantLoader()
        .load(starterGrantPath)
    // ADR-0197: the "already onboarded" fact is durable (Persistence overlay, V49) so a
    // restart does not re-run the first-time starter grant.
    val profileRepository = services.get<com.astarworks.astera.core.application.port.outbound.IPlayerProfileRepository>()
        ?: com.astarworks.astera.core.application.service.InMemoryPlayerProfileRepository().also {
            services.register<com.astarworks.astera.core.application.port.outbound.IPlayerProfileRepository>(it, priority = 0)
        }
    profileService =
        com.astarworks.astera.core.application.service.InMemoryPlayerProfileService(
            starterGrant = starterGrant,
            itemRegistry = itemContents,
            ledgerProvider = ledgerProvider,
            tutorialRegistry = tutorialContents,
            repository = profileRepository,
        )
    services.register<com.astarworks.astera.core.application.port.outbound.IPlayerProfileService>(
        profileService,
        priority = 0,
    )

    // 3. Onboarding listeners (G1 subscribe + G2 + G3).
    val createFirstTimeListener = com.astarworks.astera.core.application.eventbus.listeners
        .CreateFirstTimeProfileOnPlayerJoinedListener(
            profileService = profileService,
            eventBus = eventBus,
            scheduler = scheduler,
        )
    // First-time onboarding is owned by the lobby (ADR-0179): profiles live in
    // memory per backend, so a game-only server would treat every arrival from
    // the lobby as a new player and re-run the starter grant.
    if (ctx.serverRole.hostsLobby) {
        wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerJoinedServer::class) { event ->
            createFirstTimeListener.handle(event)
        }
    }
    val starterItemsListener = com.astarworks.astera.core.application.eventbus.listeners.onboarding
        .DeliverStarterItemsListener(
            starterItems = { profileService.loadoutFor(it) },
            delivery = { services.get<com.astarworks.astera.core.application.port.outbound.item.IItemDelivery>() },
            server = mcServer,
            sessions = sessionRepo,
        )
    wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.FirstTimeProfileCreated::class) { event ->
        starterItemsListener.handle(event)
    }
    wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerArrivedOnServer::class) { event ->
        starterItemsListener.handle(event)
    }
    val startTutorialListener = com.astarworks.astera.core.application.eventbus.listeners.onboarding
        .StartTutorialOnFirstTimeProfileCreatedListener(
            profileService = profileService,
            sequencer = tutorialSequencer,
        )
    wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.FirstTimeProfileCreated::class) { event ->
        startTutorialListener.handle(event)
    }
    val welcomeDialogListener = com.astarworks.astera.core.application.eventbus.listeners.onboarding
        .ShowWelcomeDialogOnFirstTimeProfileCreatedListener(
            profileService = profileService,
            dialogGateway = dialogGateway,
        )
    wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.FirstTimeProfileCreated::class) { event ->
        welcomeDialogListener.handle(event)
    }

    // 4. HUD listener (G6 — Currency lives in core; the LeveledUp + Combo
    //    HUD listeners are wired by their respective plugins per ADR-0022
    //    sibling isolation).
    val hudCurrencyListener = com.astarworks.astera.core.application.eventbus.listeners.hud
        .HudUpdateOnCurrencyChangedListener(hudGateway)
    wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.CurrencyChanged::class) { event ->
        hudCurrencyListener.handle(event)
    }

    // 4.1 F2-2 (ADR-0194) - energy on the vanilla food bar (replaces the WP2 boss bar)
    //     and the reload sweep on the held item. Ammo is on the status line (4.15).
    val resourcePoolForHud = services.get<com.astarworks.astera.core.application.port.outbound.IResourcePoolService>()
    val foodGatewayForHud = services.get<com.astarworks.astera.core.application.port.outbound.IPlayerFoodGateway>()
    if (resourcePoolForHud != null && foodGatewayForHud != null) {
        val energyFoodBar = com.astarworks.astera.core.application.eventbus.listeners.hud
            .EnergyFoodBarListener(resourcePoolForHud, foodGatewayForHud, scheduler)
        wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerJoinedServer::class) { energyFoodBar.onJoined(it) }
        wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerQuitServer::class) { energyFoodBar.onQuit(it) }
        wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerRespawned::class) { energyFoodBar.onRespawned(it) }
        wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.ResourceSpent::class) { energyFoodBar.onSpent(it) }
        wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.ResourceRegenned::class) { energyFoodBar.onRegenned(it) }
        wave5completeSubs += eventBus.subscribe(
            com.astarworks.astera.core.domain.event.PlayerActiveWeaponChanged::class,
        ) { energyFoodBar.onActiveWeaponChanged(it) }
    }

    // 4.15 ADR-0182 — status line (exact HP bar) and the resource pack it needs.
    //      HpStatusHudService owns the status-line channel: glyph bar once the
    //      client reports the pack LOADED, `HP cur/max` text before that.
    //      The pack is pushed on join only when the host configured one.
    val hpStatusHud = com.astarworks.astera.core.application.eventbus.listeners.hud
        .HpStatusHudService(
            health = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway>(),
            hudGateway = hudGateway,
            pools = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IResourcePoolService>(),
            items = itemContents,
            scheduler = scheduler,
            // ADR-0197: the combat plugin registers the cooldown icons later; look them up per flush.
            cooldownIcons = { services.get<com.astarworks.astera.core.application.port.outbound.ICooldownIconSource>() },
        )
    wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerJoinedServer::class) { hpStatusHud.onJoined(it) }
    wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerQuitServer::class) { hpStatusHud.onQuit(it) }
    wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.ResourcePackStatusChanged::class) { hpStatusHud.onPackStatus(it) }
    // F3-9 (ADR-0201): the pack spec is re-readable (`/astera admin pack reload`), so the
    // join listener and the diagnostics always go through the service.
    val resourcePackService = com.astarworks.astera.core.application.service.hud.ResourcePackService(
        initial = ports.resourcePack,
        loader = { com.astarworks.astera.core.platform.wiring.ResourcePackEnv.load(logger = logger) },
        hud = hudGateway,
        onlinePlayers = {
            services.get<com.astarworks.astera.core.application.port.outbound.admin.IAdminPlayerGateway>()
                ?.onlinePlayers().orEmpty()
        },
    )
    services.register<com.astarworks.astera.core.application.service.hud.ResourcePackService>(resourcePackService, priority = 0)
    val hudDiagnostics = com.astarworks.astera.core.application.eventbus.listeners.hud
        .HudDiagnosticsService(hudGateway, scheduler, packConfigured = { resourcePackService.current() != null })
    wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerJoinedServer::class) { hudDiagnostics.onJoined(it) }
    wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerQuitServer::class) { hudDiagnostics.onQuit(it) }
    wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.ResourcePackStatusChanged::class) { hudDiagnostics.onPackStatus(it) }
    val pushOnJoin = com.astarworks.astera.core.application.eventbus.listeners.hud
        .ResourcePackPushOnJoinListener(hudGateway) { resourcePackService.current() }
    wave5completeSubs += eventBus.subscribe(com.astarworks.astera.core.domain.event.PlayerJoinedServer::class) { pushOnJoin.handle(it) }
    ports.resourcePack?.let { logger.info("ADR-0182: resource pack push on join enabled (${it.url}).") }
        ?: logger.info("ADR-0182: no resource pack configured (ASTERA_RESOURCE_PACK_URL unset); status HUD uses its text form.")

    // 4.2 Wave H 5content-depth (ADR-0125) — Quest + Recipe listener wiring.
    //     Phase 1 PoC closing: `QuestStageCompleted` (Wave 5s) and
    //     `RecipeCrafted` (Wave 5s) published events with no subscriber,
    //     leaving each stage / craft as a silent drop. These two listeners
    //     surface them via the same IHudGateway / IPlayerGateway / IMessageRenderer
    //     ports the Currency listener already uses. The Achievement-side
    //     equivalent (`AchievementUnlocked`) lives in `:plugin:stats` and
    //     wires inside `AsteraStatsPlugin` per ADR-0022 sibling isolation.
    val questProgressListener = com.astarworks.astera.core.application.eventbus.listeners.quest
        .UpdateQuestProgressOnQuestStageCompletedListener(hudGateway)
    wave5completeSubs += eventBus.subscribe(
        com.astarworks.astera.core.domain.event.QuestStageCompleted::class,
    ) { event -> questProgressListener.handle(event) }

    val recipeNotifyListener = com.astarworks.astera.core.application.eventbus.listeners.recipe
        .NotifyOnRecipeCraftedListener(
            playerGateway = playerGateway,
            messages = messages,
            hudGateway = hudGateway,
        )
    wave5completeSubs += eventBus.subscribe(
        com.astarworks.astera.core.domain.event.RecipeCrafted::class,
    ) { event -> recipeNotifyListener.handle(event) }

    // 5. Travel use cases (G13/G14/G15).
    travelToPve = com.astarworks.astera.core.application.usecase.travel
        .TravelToPveWorldUseCase(
            worldGateway = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IWorldGateway>(),
            eventBus = eventBus,
            scheduler = scheduler,
        )
    travelToSurvival = com.astarworks.astera.core.application.usecase.travel
        .TravelToSurvivalIslandUseCase(
            parties = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IPartyRepository>(),
            islands = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository>(),
            islandManager = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandManager>(),
            worldGateway = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IWorldGateway>(),
            eventBus = eventBus,
            scheduler = scheduler,
        )
    returnToLobby = com.astarworks.astera.core.application.usecase.travel
        .ReturnToLobbyUseCase(
            worldGateway = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IWorldGateway>(),
            eventBus = eventBus,
            scheduler = scheduler,
        )

    // 6. Commands (G9 register left to Match plugin; G10 + G11 + G12 here).
    //    Each builder returns a Literal whose name (`travel` / `menu` /
    //    `help`) becomes the top-level command. Mirrors the way
    //    `astera-match` / `astera-queue` register under their own
    //    top-level names in `AsteraMatchPlugin`. The `astera-` prefix is
    //    rewritten so they appear under one player-facing namespace.
    commands.register(
        com.astarworks.astera.core.application.command.buildTravelCommandSpec(
            travelToPve = travelToPve,
            travelToSurvival = travelToSurvival,
            returnToLobby = returnToLobby,
        ),
    )
    commands.register(
        com.astarworks.astera.core.application.command.buildMainMenuCommandSpec(
            guiAdapter = guiAdapter,
            mcServer = mcServer,
        ),
    )

    logger.info(
        "Wave 5wireup wired: IPlayerProfileService + IDialogGateway + " +
            "IHudGateway registered; CreateFirstTimeProfile / StartTutorial / " +
            "ShowWelcomeDialog / HudUpdateOnCurrencyChanged subscribed; " +
            "/astera travel + /astera menu commands mounted.",
    )
}
