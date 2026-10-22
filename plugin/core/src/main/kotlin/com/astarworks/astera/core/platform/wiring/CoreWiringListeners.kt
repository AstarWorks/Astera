package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.adapter.api.hud.ITabList
import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.admin.IScheduledAnnouncementSystem
import com.astarworks.astera.core.application.port.outbound.afk.IAfkDetector
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register

/** Wave 5complete (chat / moderation / item lifecycle) and Wave 5block-embed listeners. */
internal fun CoreWiringState.wireCompletenessListeners() {
    // ── Wave 5complete (ADR-0117) ─────────────────────────────────────
    //
    // PvP server completeness — 6-axis MECE close on top of the
    // single-axis state machine the Wave 5wireup audit revealed.
    //
    //   Axis 2 (Communication) — register `/astera-chat` + `/astera-announce`
    //                            commands on top of Wave 5e ChatChannel +
    //                            Wave 5t IScheduledAnnouncementSystem.
    //   Axis 3 (Moderation)    — flat /astera-{kick,mute,unmute,ban,tempban,
    //                            unban,warn} commands + AFK auto-kick bridge.
    //                            Wires IModerationService default + sealed
    //                            +Warn variant landed in this wave.
    //   Axis 5 (System)        — uses the existing Wave 5q IAfkDetector + sweep
    //                            scheduler; only the kick-event subscriber is
    //                            new.
    //   Axis 6 (Item lifecycle)— register IItemEntityGateway default +
    //                            subscribe SpawnItemEntityOnLootDropped +
    //                            AutoPickupOnPlayerMoved; PlayerMovedLocationTracker
    //                            keeps the killer's last-known position so the
    //                            spawn listener has a position seed.
    //
    // Axes 1 (player main state) + 4 (UI overlay) — Match-side, wired
    // by AsteraMatchPlugin.
    val moderationService: com.astarworks.astera.core.application.port.outbound.IModerationService =
        services.get<com.astarworks.astera.core.application.port.outbound.IModerationService>()
            ?: com.astarworks.astera.core.application.service.InMemoryModerationService().also {
                services.register<com.astarworks.astera.core.application.port.outbound.IModerationService>(it, priority = 0)
            }

    val locationTrackerImpl = com.astarworks.astera.core.application.service.PlayerMovedLocationTracker(eventBus)
    services.register<com.astarworks.astera.core.application.port.outbound.IPlayerLocationLookup>(locationTrackerImpl, priority = 0)
    wave5completeSubs += locationTrackerImpl.start()
    locationTracker = locationTrackerImpl

    val itemEntityGateway: com.astarworks.astera.core.application.port.outbound.item.IItemEntityGateway =
        services.get<com.astarworks.astera.core.application.port.outbound.item.IItemEntityGateway>()
            ?: com.astarworks.astera.core.application.service.item.InMemoryItemEntityGateway(scheduler).also {
                services.register<com.astarworks.astera.core.application.port.outbound.item.IItemEntityGateway>(it, priority = 0)
            }

    val spawnOnLoot = com.astarworks.astera.core.application.eventbus.listeners.item
        .SpawnItemEntityOnLootDroppedListener(
            itemEntityGateway = itemEntityGateway,
            locationLookup = locationTrackerImpl,
        )
    wave5completeSubs += eventBus.subscribe(
        com.astarworks.astera.core.domain.event.LootDropped::class,
    ) { spawnOnLoot.handle(it) }

    val autoPickup = com.astarworks.astera.core.application.eventbus.listeners.item
        .AutoPickupOnPlayerMovedListener(
            itemEntityGateway = itemEntityGateway,
            inventoryMutator = inventoryMutator,
            eventBus = eventBus,
        )
    wave5completeSubs += eventBus.subscribe(
        com.astarworks.astera.core.domain.event.PlayerMoved::class,
    ) { autoPickup.handle(it) }

    val kickOnAfk = com.astarworks.astera.core.application.eventbus.listeners.afk
        .KickPlayerOnAfkKickedListener(moderationService)
    wave5completeSubs += eventBus.subscribe(
        com.astarworks.astera.core.domain.event.PlayerAfkKicked::class,
    ) { kickOnAfk.handle(it) }

    // ── Wave I 5match-wireup-fix (ADR-0126 §D + §E) ──────────────────
    // AFK warning ActionBar + cross-world loading subtitle. Both are
    // private-beta blockers — playtesters could not tell the AFK
    // sweeper was about to kick them, nor whether a teleport had
    // actually completed. Wired here next to KickPlayerOnAfkKicked
    // because both consume the same AFK domain events; the loading
    // listener subscribes to the Round 8 travel events.
    val afkWarningHud = com.astarworks.astera.core.application.eventbus.listeners.afk
        .AfkWarningHudListener(hudGateway)
    wave5completeSubs += eventBus.subscribe(
        com.astarworks.astera.core.domain.event.PlayerAfkWarned::class,
    ) { afkWarningHud.onWarned(it) }
    wave5completeSubs += eventBus.subscribe(
        com.astarworks.astera.core.domain.event.PlayerAfkKicked::class,
    ) { afkWarningHud.onKicked(it) }

    val loadingProgress = com.astarworks.astera.core.application.eventbus.listeners.loading
        .LoadingProgressListener(hudGateway)
    wave5completeSubs += eventBus.subscribe(
        com.astarworks.astera.core.domain.event.PlayerTraveledToPveWorld::class,
    ) { loadingProgress.onTraveledToPve(it) }
    wave5completeSubs += eventBus.subscribe(
        com.astarworks.astera.core.domain.event.PlayerArrivedAtIsland::class,
    ) { loadingProgress.onArrivedAtIsland(it) }
    wave5completeSubs += eventBus.subscribe(
        com.astarworks.astera.core.domain.event.PlayerReturnedToLobby::class,
    ) { loadingProgress.onReturnedToLobby(it) }

    // Server-population / queue / wallet sidebar: owned by the match plugin's
    // LobbySidebarService since WP14 (ADR-0177) — one producer per HUD channel.

    // Wave AC 5onboarding-flow (ADR-0139): rotating tip on every join.
    // Sibling to FirstTimeProfileCreated listeners (welcome dialog +
    // tutorial auto-start) which fire only once-per-account; this
    // listener fires on every PlayerJoinedServer for ongoing discovery.
    val hintListener = com.astarworks.astera.core.application.eventbus.listeners.onboarding
        .HintOnPlayerJoinedListener(playerGateway, messages)
    wave5completeSubs += eventBus.subscribe(PlayerJoinedServer::class) { event ->
        hintListener.handle(event)
    }

    // Wave AB 5lobby-ux-complete (ADR-0138): chat display + tab list
    // refresh. Pre-Wave-AB ChatMessageSent had only audit + replay
    // subscribers (no UX path); chat sent was invisible to other
    // players. ITabList registered at priority 0 but no listener
    // populated entries on join/quit (vanilla MC default fallback).
    // Both closed here using Core-only ports (IPlayerGateway +
    // IPlayerProfileService + IPlayerSessionRepository + ITabList).
    val chatDisplayListener = com.astarworks.astera.core.application.eventbus.listeners.chat
        .ChatDisplayListener(playerGateway, sessionRepo, profileService)
    wave5completeSubs += eventBus.subscribe(
        com.astarworks.astera.core.domain.event.ChatMessageSent::class,
    ) { event ->
        chatDisplayListener.handle(event)
    }
    val tabListRefreshListener = com.astarworks.astera.core.application.eventbus.listeners.hud
        .UpdateTabListOnPlayerJoinedListener(tabList, sessionRepo, profileService)
    wave5completeSubs += eventBus.subscribe(PlayerJoinedServer::class) { event ->
        tabListRefreshListener.handle(event)
    }
    wave5completeSubs += eventBus.subscribe(
        com.astarworks.astera.core.domain.event.PlayerQuitServer::class,
    ) { event ->
        tabListRefreshListener.handle(event)
    }

    // Apply / Lift use cases (shared by flat commands + the existing
    // /astera mod tree if Wave 5h later wires it up).
    val applyMod = com.astarworks.astera.core.application.usecase.moderation
        .ApplyModerationActionUseCase(moderationService, permissionService)
    val liftMod = com.astarworks.astera.core.application.usecase.moderation
        .LiftModerationActionUseCase(moderationService, permissionService)

    val flatModCommands = com.astarworks.astera.core.application.command.buildModerationFlatCommands(
        apply = applyMod,
        lift = liftMod,
        service = moderationService,
        playerResolver = playerGateway,
        permService = permissionService,
    )
    flatModCommands.kick.copy(name = "astera-kick").let { commandHandler.register(it) }
    flatModCommands.mute.copy(name = "astera-mute").let { commandHandler.register(it) }
    flatModCommands.unmute.copy(name = "astera-unmute").let { commandHandler.register(it) }
    flatModCommands.ban.copy(name = "astera-ban").let { commandHandler.register(it) }
    flatModCommands.tempban.copy(name = "astera-tempban").let { commandHandler.register(it) }
    flatModCommands.unban.copy(name = "astera-unban").let { commandHandler.register(it) }
    flatModCommands.warn.copy(name = "astera-warn").let { commandHandler.register(it) }

    commandHandler.register(
        com.astarworks.astera.core.application.command.buildChatCommandSpec(
            sendChat = com.astarworks.astera.core.application.usecase.chat.SendChatMessageUseCase(
                eventBus = eventBus,
                moderationService = moderationService,
            ),
            teamLookup = { services.get<com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup>() },
            partyRepository = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IPartyRepository>(),
            playerResolver = playerGateway,
        ).copy(name = "astera-chat"),
    )

    commandHandler.register(
        com.astarworks.astera.core.application.command.buildAnnouncementCommandSpec(
            announcer = announcementSystem,
            permissionService = permissionService,
        ).copy(name = "astera-announce"),
    )

    logger.info(
        "Wave 5complete wired: IModerationService + IItemEntityGateway + " +
            "IPlayerLocationLookup registered; KickPlayerOnAfkKicked + " +
            "SpawnItemEntityOnLootDropped + AutoPickupOnPlayerMoved + " +
            "PlayerMovedLocationTracker subscribed; /astera-{kick,mute,unmute," +
            "ban,tempban,unban,warn,chat,announce} commands registered.",
    )

    // ── Wave 5block-embed (ADR-0120) ──────────────────────────────────
    //
    // Block command / event embedding — close the four block-audit gaps
    // (no sign action service, no IBlockInteractionRouter, no portal
    // abstraction, no region transition detector) by:
    //
    //  - Registering IBlockInteractionRouter / ISignActionService /
    //    IPortalGateway InMemory defaults.
    //  - Subscribing RouteBlockInteractionListener to PlayerInteractedBlock
    //    so the previously-zero-subscriber event finally has a sink.
    //  - Subscribing ActivatePortalOnPlayerMoved /
    //    ActivatePortalOnPlayerInteractedBlock for the two portal
    //    activation pathways.
    //  - Subscribing RegionTransitionDetectorListener to PlayerMoved
    //    so PlayerEnteredRegion / PlayerExitedRegion start flowing.
    //
    // The CustomBlockSpec.onRightClick: GuiIntent? field extension
    // (ADR-0090 → ADR-0120) is consumed at materializer time (Wave 6b);
    // here we only need the registry plumbing already in place.
    val blockInteractionRouter: com.astarworks.astera.core.application.port.outbound.block.IBlockInteractionRouter =
        services.get<com.astarworks.astera.core.application.port.outbound.block.IBlockInteractionRouter>()
            ?: com.astarworks.astera.core.application.service.block.InMemoryBlockInteractionRouter().also {
                services.register<com.astarworks.astera.core.application.port.outbound.block.IBlockInteractionRouter>(it, priority = 0)
            }

    val signActionService: com.astarworks.astera.core.application.port.outbound.sign.ISignActionService =
        services.get<com.astarworks.astera.core.application.port.outbound.sign.ISignActionService>()
            ?: com.astarworks.astera.core.application.service.sign.InMemorySignActionService().also {
                services.register<com.astarworks.astera.core.application.port.outbound.sign.ISignActionService>(it, priority = 0)
            }

    // Wave 6a-7 (ADR-0152 §B): PaperSignActionService overlays InMemory
    // at priority 100. Adds advisory Bukkit Sign blockstate validation
    // at bind time (DEBUG log on world-unloaded / non-sign block —
    // binding still recorded; preserves YAML-preload flow).
    ports.signActionService?.let {
        services.register<com.astarworks.astera.core.application.port.outbound.sign.ISignActionService>(
            it,
            priority = 100,
        )
    }

    val portalGateway: com.astarworks.astera.core.application.port.outbound.portal.IPortalGateway =
        services.get<com.astarworks.astera.core.application.port.outbound.portal.IPortalGateway>()
            ?: com.astarworks.astera.core.application.service.portal.InMemoryPortalGateway().also {
                services.register<com.astarworks.astera.core.application.port.outbound.portal.IPortalGateway>(it, priority = 0)
            }

    // RouteBlockInteractionListener / portal activation listeners need
    // a world gateway for the teleport dispatch sink. Reuse the same
    // lookup pattern the effects subsystem uses: prefer a registered
    // IWorldGateway impl (Wave 6 Paper adapter), fall back to the
    // Noop one. The Noop teleport returns false and the listeners
    // log DEBUG + skip — Phase 1 PoC value, since the routing surface
    // itself is the deliverable; the actual teleport lands in Wave 6.
    val worldGatewayForBlockRoute: com.astarworks.astera.core.application.port.outbound.IWorldGateway =
        services.get<com.astarworks.astera.core.application.port.outbound.IWorldGateway>()
            ?: com.astarworks.astera.core.application.service.effect.NoopWorldGatewayForEffects()

    val routeBlockInteraction = com.astarworks.astera.core.application.eventbus.listeners.block
        .RouteBlockInteractionListener(
            router = blockInteractionRouter,
            guiAdapter = guiAdapter,
            worldGateway = worldGatewayForBlockRoute,
            mcServer = mcServer,
            eventBus = eventBus,
        )
    wave5completeSubs += eventBus.subscribe(
        com.astarworks.astera.core.domain.event.PlayerInteractedBlock::class,
    ) { routeBlockInteraction.handle(it) }

    val activatePortalOnMoved = com.astarworks.astera.core.application.eventbus.listeners.portal
        .ActivatePortalOnPlayerMovedListener(
            portalGateway = portalGateway,
            worldGateway = worldGatewayForBlockRoute,
            eventBus = eventBus,
        )
    wave5completeSubs += eventBus.subscribe(
        com.astarworks.astera.core.domain.event.PlayerMoved::class,
    ) { activatePortalOnMoved.handle(it) }

    val activatePortalOnInteract = com.astarworks.astera.core.application.eventbus.listeners.portal
        .ActivatePortalOnPlayerInteractedBlockListener(
            portalGateway = portalGateway,
            worldGateway = worldGatewayForBlockRoute,
            eventBus = eventBus,
        )
    wave5completeSubs += eventBus.subscribe(
        com.astarworks.astera.core.domain.event.PlayerInteractedBlock::class,
    ) { activatePortalOnInteract.handle(it) }

    val regionTransitionDetector = com.astarworks.astera.core.application.eventbus.listeners.region
        .RegionTransitionDetectorListener(
            stageRegionIndex = stageRegionIndex,
            eventBus = eventBus,
        )
    wave5completeSubs += eventBus.subscribe(
        com.astarworks.astera.core.domain.event.PlayerMoved::class,
    ) { regionTransitionDetector.handle(it) }

    // ParseAndBindSignOnSignChangedListener is constructed but not yet
    // subscribed — the Bukkit SignChangeEvent → domain event bridge
    // lands in Wave 6b. Construction now keeps the binding pathway
    // exercised and ensures the wiring closure is committed.
    @Suppress("UNUSED_VARIABLE")
    val parseAndBindSign = com.astarworks.astera.core.application.eventbus.listeners.sign
        .ParseAndBindSignOnSignChangedListener(signActionService)

    logger.info(
        "Wave 5block-embed wired: IBlockInteractionRouter + ISignActionService + " +
            "IPortalGateway registered; RouteBlockInteraction + " +
            "ActivatePortalOn{PlayerMoved,PlayerInteractedBlock} + " +
            "RegionTransitionDetector subscribed; " +
            "ParseAndBindSignOnSignChanged constructed (Wave 6b event bridge pending).",
    )
}
