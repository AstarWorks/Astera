package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.application.port.outbound.IFriendshipRepository
import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.application.port.outbound.admin.IScheduledAnnouncementSystem
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import java.nio.file.Path

/** Server hardening, lobby / settings / social intent handlers and server content ops. */
internal fun CoreWiringState.wireServerHardenAndLobby() {
    // ── Wave 5server-harden (ADR-0123) — server hardening + safety ────
    // Three substrates land here:
    //  1. IContentValidator: boot-time cross-content reference walk +
    //     mode-gated abort on Critical (Lenient default; flip to Strict
    //     once content/ is stable).
    //  2. IErrorReporter: structured fan-out to audit log + admin
    //     broadcast for Critical.
    //  3. IVanillaFeatureSuppressor: pluggable register the Wave 6b
    //     Paper adapter consumes to actually cancel vanilla events.
    // The boot lifecycle listener for the validator is registered with
    // the InMemoryServerBootstrapper so it fires on onReady() (after
    // every plugin's onEnable, before scheduler-driven tasks start).
    val contentValidator: com.astarworks.astera.core.application.port.outbound.validation.IContentValidator =
        com.astarworks.astera.core.application.service.validation.ContentValidatorService()
    services.register<com.astarworks.astera.core.application.port.outbound.validation.IContentValidator>(
        contentValidator,
        priority = 0,
    )

    val auditRepoForReporter = services.get<com.astarworks.astera.core.application.port.outbound.IAuditLogRepository>()
    val errorReporter: com.astarworks.astera.core.application.port.outbound.error.IErrorReporter =
        if (auditRepoForReporter != null) {
            com.astarworks.astera.core.application.service.error.InMemoryErrorReporter(auditRepoForReporter)
        } else {
            com.astarworks.astera.core.application.service.error.InMemoryErrorReporter(auditRepo)
        }
    services.register<com.astarworks.astera.core.application.port.outbound.error.IErrorReporter>(
        errorReporter,
        priority = 0,
    )

    val vanillaSuppressor:
        com.astarworks.astera.core.application.port.outbound.vanilla.IVanillaFeatureSuppressor =
        com.astarworks.astera.core.application.service.vanilla.InMemoryVanillaFeatureSuppressor()
    services.register<com.astarworks.astera.core.application.port.outbound.vanilla.IVanillaFeatureSuppressor>(
        vanillaSuppressor,
        priority = 0,
    )

    // Wire the bootstrapper if a feature plugin or Wave 6a Paper adapter
    // hasn't pre-registered one. The boot-time validate listener attaches
    // to the bootstrapper so onReady() drives validateAll().
    val bootstrapper: com.astarworks.astera.core.application.port.outbound.lifecycle.IServerBootstrapper =
        services.get<com.astarworks.astera.core.application.port.outbound.lifecycle.IServerBootstrapper>()
            ?: com.astarworks.astera.core.application.service.lifecycle.InMemoryServerBootstrapper(eventBus)
                .also {
                    services.register<com.astarworks.astera.core.application.port.outbound.lifecycle.IServerBootstrapper>(
                        it,
                        priority = 0,
                    )
                }
    val bootValidateListener =
        com.astarworks.astera.core.application.service.validation.BootValidateContentLifecycleListener(
            validator = contentValidator,
            mode = com.astarworks.astera.core.application.service.validation
                .BootValidateContentLifecycleListener.Mode.Lenient,
            // Wave L (ADR-0129): fan-out boot validation criticals via
            // the error reporter (admin broadcast + audit row).
            errorReporter = errorReporter,
        )
    bootstrapper.register(bootValidateListener)

    // ── Wave L 5hardening-complete (ADR-0129) — wire the dormant ports ──
    // The three Wave 5server-harden ports (IContentValidator /
    // IErrorReporter / IVanillaFeatureSuppressor) shipped but had **0**
    // call sites in production. Wave L closes the gap.
    //
    // (1) Default-suppress vanilla features on ServerReady. Subscribes via
    //     the in-process event bus so cancel-on-disable matches the rest
    //     of the listener fleet (ADR-0123 §D contract).
    val suppressVanillaListener =
        com.astarworks.astera.core.application.eventbus.listeners.lifecycle
            .SuppressVanillaFeaturesOnServerBootListener(vanillaSuppressor)
    wave5completeSubs += eventBus.subscribe(
        com.astarworks.astera.core.domain.event.lifecycle.ServerReady::class,
    ) { suppressVanillaListener.handle(it) }

    // (2) Broadcast Critical errors via the announcement system to admin
    //     chat. announcementSystem was created earlier (Wave 5t block) so
    //     it's in scope here.
    val broadcastCriticalErrorListener =
        com.astarworks.astera.core.application.eventbus.listeners.error
            .BroadcastCriticalErrorListener(
                errorReporter = errorReporter,
                announcer = announcementSystem,
            )
    // The listener owns its own subscription handle (mirrors the
    // Wave I AnnihilationWinConditionListener pattern); we retain it
    // as a dedicated field because IErrorReporter.Subscription is a
    // distinct type from IDomainEventBus.Subscription (the
    // wave5completeSubs list element type).
    broadcastCriticalErrorSub = broadcastCriticalErrorListener.subscribe()

    logger.info(
        "Wave 5server-harden wired: IContentValidator + IErrorReporter + " +
            "IVanillaFeatureSuppressor registered; BootValidateContentLifecycleListener " +
            "attached to IServerBootstrapper (mode=Lenient) with errorReporter fan-out. " +
            "Wave L (ADR-0129): SuppressVanillaFeaturesOnServerBootListener subscribed " +
            "(seeds ${com.astarworks.astera.core.application.eventbus.listeners.lifecycle
                .SuppressVanillaFeaturesOnServerBootListener.DEFAULT_SUPPRESSED.size} default-suppress features).",
    )

    // ── Wave G 5player-prefs-social (ADR-0124) — settings + friends + party ──
    //
    // Wires the player-self preference + social GUI surface end-to-end:
    //  - 2 new setter use cases (SetLocaleUseCase / SetVolumeUseCase)
    //  - 2 new party use cases (KickPartyMemberUseCase / TransferPartyLeaderUseCase)
    //  - 9 lobby intent handlers (3 settings, 4 friends, 4 party)
    //  - the show handlers already shipped by Wave 5lobby-content but
    //    never wired — we wire those here too so the GUI surface is
    //    end-to-end live (settings/friends/party show + close from main menu)
    //  - 4 Brigadier command families (`/astera lang`, `/astera volume`,
    //    `/astera-friend-*`, `/astera-party-*`)
    //
    // Settings/Friends/Party share the same IGuiIntentRouter — registrations
    // collect into `waveGRegistrations` so onDisable can release classloader
    // retention per IGuiIntentRouter contract.
    val setLocale = com.astarworks.astera.core.application.usecase.settings.SetLocaleUseCase(settingsRepo)
    val setVolume = com.astarworks.astera.core.application.usecase.settings.SetVolumeUseCase(settingsRepo)
    val setControlScheme = com.astarworks.astera.core.application.usecase.settings.SetControlSchemeUseCase(settingsRepo)
    val friendshipRepo = services.getOrThrow<IFriendshipRepository>()
    val partyRepoForWaveG = services.getOrThrow<IPartyRepository>()
    val sendFriendRequest = com.astarworks.astera.core.application.usecase.social.SendFriendRequestUseCase(friendshipRepo)
    val acceptFriendRequest = com.astarworks.astera.core.application.usecase.social.AcceptFriendRequestUseCase(friendshipRepo)
    val removeFriend = com.astarworks.astera.core.application.usecase.social.RemoveFriendUseCase(friendshipRepo)
    val createParty = com.astarworks.astera.core.application.usecase.social.CreatePartyUseCase(partyRepoForWaveG)
    val joinParty = com.astarworks.astera.core.application.usecase.social.JoinPartyUseCase(partyRepoForWaveG)
    val leaveParty = com.astarworks.astera.core.application.usecase.social.LeavePartyUseCase(partyRepoForWaveG)
    val kickPartyMember = com.astarworks.astera.core.application.usecase.social.KickPartyMemberUseCase(partyRepoForWaveG)
    val transferPartyLeader = com.astarworks.astera.core.application.usecase.social.TransferPartyLeaderUseCase(partyRepoForWaveG)

    val waveGRegistrations = mutableListOf<com.astarworks.astera.core.application.services.ServiceRegistry.Registration>()
    val waveGRouter = services.getOrThrow<com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter>()

    // Settings — show + 3 application handlers
    val settingsShowHandler = com.astarworks.astera.core.application.gui.lobby.intent.LobbySettingsShowIntentHandler(
        guiAdapter = guiAdapter,
        mcServer = mcServer,
        messages = messages,
        playerGateway = playerGateway,
    )
    val settingsLocaleHandler = com.astarworks.astera.core.application.gui.lobby.intent.LobbySettingsLocaleIntentHandler(
        setLocale = setLocale,
        settings = settingsRepo,
        guiAdapter = guiAdapter,
        messages = messages,
        playerGateway = playerGateway,
    )
    val settingsVolumeHandler = com.astarworks.astera.core.application.gui.lobby.intent.LobbySettingsVolumeIntentHandler(
        setVolume = setVolume,
        settings = settingsRepo,
        messages = messages,
        playerGateway = playerGateway,
    )
    val settingsControlsHandler = com.astarworks.astera.core.application.gui.lobby.intent.LobbySettingsControlsIntentHandler(
        setControlScheme = setControlScheme,
        settings = settingsRepo,
        messages = messages,
        playerGateway = playerGateway,
    )
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent.LobbySettingsShowIntentHandler.INTENT_SHOW,
    ) { intent, player -> settingsShowHandler.handle(intent, player) }
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent.LobbySettingsLocaleIntentHandler.INTENT_CYCLE,
    ) { intent, player -> settingsLocaleHandler.handle(intent, player) }
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent.LobbySettingsVolumeIntentHandler.INTENT_ADJUST,
    ) { intent, player -> settingsVolumeHandler.handle(intent, player) }
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent.LobbySettingsControlsIntentHandler.INTENT_CYCLE,
    ) { intent, player -> settingsControlsHandler.handle(intent, player) }

    // Friends — show + 4 application handlers
    val friendsShowHandler = com.astarworks.astera.core.application.gui.lobby.intent.LobbyFriendsShowIntentHandler(
        guiAdapter = guiAdapter,
        mcServer = mcServer,
        messages = messages,
        playerGateway = playerGateway,
    )
    val friendAddHandler = com.astarworks.astera.core.application.gui.lobby.intent.LobbyFriendAddIntentHandler(
        sendFriendRequest = sendFriendRequest,
        guiAdapter = guiAdapter,
        messages = messages,
        playerGateway = playerGateway,
    )
    val friendAcceptHandler = com.astarworks.astera.core.application.gui.lobby.intent.LobbyFriendAcceptIntentHandler(
        acceptFriendRequest = acceptFriendRequest,
        messages = messages,
        playerGateway = playerGateway,
    )
    val friendRemoveHandler = com.astarworks.astera.core.application.gui.lobby.intent.LobbyFriendRemoveIntentHandler(
        removeFriend = removeFriend,
        messages = messages,
        playerGateway = playerGateway,
    )
    val friendsPendingHandler = com.astarworks.astera.core.application.gui.lobby.intent.LobbyFriendsPendingIntentHandler(
        friendships = friendshipRepo,
        mcServer = mcServer,
        messages = messages,
        playerGateway = playerGateway,
    )
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent.LobbyFriendsShowIntentHandler.INTENT_SHOW,
    ) { intent, player -> friendsShowHandler.handle(intent, player) }
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent.LobbyFriendAddIntentHandler.INTENT_ADD,
    ) { intent, player -> friendAddHandler.handle(intent, player) }
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent.LobbyFriendAcceptIntentHandler.INTENT_ACCEPT,
    ) { intent, player -> friendAcceptHandler.handle(intent, player) }
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent.LobbyFriendRemoveIntentHandler.INTENT_REMOVE,
    ) { intent, player -> friendRemoveHandler.handle(intent, player) }
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent.LobbyFriendsPendingIntentHandler.INTENT_PENDING,
    ) { intent, player -> friendsPendingHandler.handle(intent, player) }

    // Party — show + 4 application handlers
    val partyShowHandler = com.astarworks.astera.core.application.gui.lobby.intent.LobbyPartyShowIntentHandler(
        guiAdapter = guiAdapter,
        mcServer = mcServer,
        messages = messages,
        playerGateway = playerGateway,
    )
    val partyInviteHandler = com.astarworks.astera.core.application.gui.lobby.intent.LobbyPartyInviteIntentHandler(
        joinParty = joinParty,
        parties = partyRepoForWaveG,
        guiAdapter = guiAdapter,
        messages = messages,
        playerGateway = playerGateway,
    )
    val partyKickHandler = com.astarworks.astera.core.application.gui.lobby.intent.LobbyPartyKickIntentHandler(
        kickPartyMember = kickPartyMember,
        messages = messages,
        playerGateway = playerGateway,
    )
    val partyTransferLeaderHandler = com.astarworks.astera.core.application.gui.lobby.intent.LobbyPartyTransferLeaderIntentHandler(
        transferLeader = transferPartyLeader,
        messages = messages,
        playerGateway = playerGateway,
    )
    val partyLeaveHandler = com.astarworks.astera.core.application.gui.lobby.intent.LobbyPartyLeaveIntentHandler(
        leaveParty = leaveParty,
        guiAdapter = guiAdapter,
        messages = messages,
        playerGateway = playerGateway,
    )
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent.LobbyPartyShowIntentHandler.INTENT_SHOW,
    ) { intent, player -> partyShowHandler.handle(intent, player) }
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent.LobbyPartyInviteIntentHandler.INTENT_INVITE,
    ) { intent, player -> partyInviteHandler.handle(intent, player) }
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent.LobbyPartyKickIntentHandler.INTENT_KICK,
    ) { intent, player -> partyKickHandler.handle(intent, player) }
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent.LobbyPartyTransferLeaderIntentHandler.INTENT_TRANSFER,
    ) { intent, player -> partyTransferLeaderHandler.handle(intent, player) }
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent.LobbyPartyLeaveIntentHandler.INTENT_LEAVE,
    ) { intent, player -> partyLeaveHandler.handle(intent, player) }

    // F3-3: customize sub-menu, profile card and personal vault buttons. The profile
    // card pulls level / stats / wallet lazily from the plugins that publish them.
    val customizeShowHandler = com.astarworks.astera.core.application.gui.lobby.intent
        .LobbyCustomizeShowIntentHandler(
            guiAdapter = guiAdapter,
            mcServer = mcServer,
            messages = messages,
            playerGateway = playerGateway,
        )
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent
            .LobbyCustomizeShowIntentHandler.INTENT_PREFIX,
    ) { intent, player -> customizeShowHandler.handle(intent, player) }

    val profileShowHandler = com.astarworks.astera.core.application.gui.lobby.intent
        .LobbyProfileShowIntentHandler(
            guiAdapter = guiAdapter,
            mcServer = mcServer,
            messages = messages,
            playerGateway = playerGateway,
            levels = { services.get<com.astarworks.astera.core.application.port.outbound.IPlayerLevelView>() },
            stats = { services.get<com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository>() },
            wallet = { services.get<com.astarworks.astera.core.application.port.outbound.ICurrencyLedger>() },
        )
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent
            .LobbyProfileShowIntentHandler.INTENT_PREFIX,
    ) { intent, player -> profileShowHandler.handle(intent, player) }

    val vaultOpenHandler = com.astarworks.astera.core.application.gui.lobby.intent
        .LobbyVaultOpenIntentHandler(
            access = { services.getOrThrow<com.astarworks.astera.core.application.usecase.vault.AccessPersonalVaultUseCase>() },
            messages = messages,
            playerGateway = playerGateway,
        )
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent
            .LobbyVaultOpenIntentHandler.INTENT_OPEN,
    ) { intent, player -> vaultOpenHandler.handle(intent, player) }

    // ── Wave M 5polish-quickwin (ADR-0130 §B) ──────────────────────
    // Achievement viewer GUI intent handler. The GUI spec lives in core
    // (declarative only); the per-tile binding resolver lives in
    // :plugin:stats (sibling-isolation kept) and registers itself onto
    // the composite resolver at AsteraStatsPlugin.onEnable. Same router
    // family as the other lobby intent handlers; cleanup folds into
    // waveGRegistrations.
    val achievementShowHandler = com.astarworks.astera.core.application.gui.lobby.intent
        .LobbyAchievementShowIntentHandler(
            guiAdapter = guiAdapter,
            mcServer = mcServer,
            messages = messages,
            playerGateway = playerGateway,
        )
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent
            .LobbyAchievementShowIntentHandler.INTENT_PREFIX,
    ) { intent, player -> achievementShowHandler.handle(intent, player) }

    // Main menu `lobby.travel.show` tile → travel destination sub-menu.
    val travelShowHandler = com.astarworks.astera.core.application.gui.lobby.intent.LobbyTravelShowIntentHandler(
        travelToPve = travelToPve,
        travelToSurvival = travelToSurvival,
        returnToLobby = returnToLobby,
        guiAdapter = guiAdapter,
        mcServer = mcServer,
        messages = messages,
        playerGateway = playerGateway,
    )
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent
            .LobbyTravelShowIntentHandler.INTENT_PREFIX,
    ) { intent, player -> travelShowHandler.handle(intent, player) }

    // F3-11 (ADR-0200): lobby training range + the kit's "Training" button.
    registerLobbyTrainingRange()
    val trainingTravelHandler = com.astarworks.astera.core.application.gui.lobby.intent.LobbyTrainingTravelIntentHandler(
        range = { services.get<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyTrainingRange>() },
        worlds = { services.get<com.astarworks.astera.core.application.port.outbound.IWorldGateway>() },
        messages = messages,
        playerGateway = playerGateway,
    )
    waveGRegistrations += waveGRouter.register(
        com.astarworks.astera.core.application.gui.lobby.intent.LobbyTrainingTravelIntentHandler.INTENT_TRAVEL,
    ) { intent, player -> trainingTravelHandler.handle(intent, player) }

    ctx.registrations += waveGRegistrations

    // Brigadier command families.
    val playerPrefsCommands = com.astarworks.astera.core.application.command.PlayerPrefsCommands(
        setLocale = setLocale,
        setVolume = setVolume,
    )
    playerPrefsCommands.build().forEach { commands.register(it) }

    val friendCommands = com.astarworks.astera.core.application.command.FriendCommands(
        sendFriendRequest = sendFriendRequest,
        acceptFriendRequest = acceptFriendRequest,
        removeFriend = removeFriend,
        friendships = friendshipRepo,
        playerGateway = playerGateway,
        mcServer = mcServer,
    )
    friendCommands.build().forEach { commands.register("friend", it) }

    val partyCommands = com.astarworks.astera.core.application.command.PartyCommands(
        createParty = createParty,
        joinParty = joinParty,
        leaveParty = leaveParty,
        kickPartyMember = kickPartyMember,
        transferLeader = transferPartyLeader,
        parties = partyRepoForWaveG,
        playerGateway = playerGateway,
        mcServer = mcServer,
    )
    partyCommands.build().forEach { commands.register("party", it) }

    logger.info(
        "Wave G 5player-prefs-social wired: 9 lobby intent handlers " +
            "(settings/friends/party) + 4 Brigadier command families " +
            "(/astera lang|volume, /astera friend *, /astera party *); " +
            "${waveGRegistrations.size} intent registrations recorded for onDisable cleanup.",
    )

    // ── Lobby NPCs (WP13) — content/lobby-npcs/*.yaml ──────────────────
    // Spawns each NPC through IEntitySpawner, binds its click intents on
    // INpcInteractionRouter and (Paper only) gives it a clickable body. The
    // intent handlers (e.g. Economy's `lobby.shop.open`) register in their own
    // plugins; dispatch happens at click time, so enable order does not matter.
    // Game-only backends (ServerRole.GAME) have no lobby world, so no lobby NPCs.
    if (ctx.serverRole.hostsLobby) {
        // Queue lane pads (F3-1): step-on detection + the live per-lane queued counts. The lane list
        // comes from the Match plugin's ILobbyLaneCatalog at use time (Match enables after Core).
        when (val lobbyContent = com.astarworks.astera.core.application.service.lobby.LobbyWorldContent.load(contentDir)) {
            is com.astarworks.astera.core.domain.Result.Success -> {
                val lobbyConfig = lobbyContent.value
                services.register<com.astarworks.astera.core.application.service.lobby.LobbyLanePadService>(
                    com.astarworks.astera.core.application.service.lobby.LobbyLanePadService(
                        onWalk = { player, world, at ->
                            if (services.get<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyTrainingRange>()?.contains(world, at) == true) {
                                tutorialSequencer.recordAction(player, "range")
                            }
                        },
                        lobbyWorld = com.astarworks.astera.core.domain.world.WorldId(lobbyConfig.id),
                        centerX = lobbyConfig.center.x,
                        centerZ = lobbyConfig.center.z,
                        surfaceY = lobbyConfig.surfaceY,
                        catalog = { services.get<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyLaneCatalog>() },
                        eventBus = eventBus,
                    ),
                )
                services.register<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyLaneBoard>(
                    com.astarworks.astera.core.application.service.lobby.InMemoryLobbyLaneBoard(),
                )
            }
            is com.astarworks.astera.core.domain.Result.Failure ->
                logger.warning("Lobby lane pads disabled: lobby world content unavailable (${lobbyContent.error}).")
        }
        val lobbyNpcBodies = ports.npcBodies?.invoke(services)
        if (lobbyNpcBodies != null) {
            services.register<com.astarworks.astera.core.application.port.outbound.entity.INpcBodyMaterializer>(
                lobbyNpcBodies,
                priority = 100,
            )
        }
        val lobbyNpcReport = com.astarworks.astera.core.application.service.entity.LobbyNpcLoaderService(
            spawner = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner>(),
            interactions = services.getOrThrow<com.astarworks.astera.core.application.port.outbound.entity.INpcInteractionRouter>(),
            bodies = lobbyNpcBodies,
        ).loadFrom(contentDir.resolve("lobby-npcs"))
        logger.info("Lobby NPCs loaded: ${lobbyNpcReport.loaded} ok, ${lobbyNpcReport.failed} failed")
    } else {
        logger.info("Lobby NPCs skipped (server role ${ctx.serverRole}).")
    }

    // ── Wave J 5server-content-ops (ADR-0127) ─────────────────────────
    //
    // Three additions, all pure command + content scaffold (no new ports,
    // no Konsist rule, no DB migration):
    //
    //  - `/astera admin restart <minutes> [reason]` — graceful, announced restart
    //    via the existing Wave 5t IScheduledAnnouncementSystem (chat at
    //    now, Title waypoints at -30s/-10s/-3s/-1s) + Wave 5w
    //    IServerBootstrapper.shutdown(Restart) scheduled at -1s.
    //  - `/astera news [limit]` — top-N non-expired NewsEntry, surfaced
    //    from content/news/*.yaml via NewsContents (YamlDynamicContents
    //    substrate).
    //  - `/astera patch [version]` — latest or version-specific
    //    ChangelogEntry, from content/changelog/*.yaml.
    //  - Login bonus — LoginBonusContents + login-streak repository +
    //    DefaultLoginBonusService (claim flow: ADR-0210).
    val newsContentDir: Path = contentDir.resolve("news")
    val newsContents = com.astarworks.astera.core.application.contents.NewsContents()
    val newsReport = newsContents.bootstrap(newsContentDir)
    logger.info("News loaded: ${newsReport.loaded} ok, ${newsReport.failed} failed")
    services.register<com.astarworks.astera.core.application.port.outbound.news.INewsRegistry>(newsContents, priority = 0)
    reloadRegistry.register(newsContents, newsContentDir)

    val changelogContentDir: Path = contentDir.resolve("changelog")
    val changelogContents = com.astarworks.astera.core.application.contents.ChangelogContents()
    val changelogReport = changelogContents.bootstrap(changelogContentDir)
    logger.info("Changelog loaded: ${changelogReport.loaded} ok, ${changelogReport.failed} failed")
    services.register<com.astarworks.astera.core.application.port.outbound.changelog.IChangelogRegistry>(changelogContents, priority = 0)
    reloadRegistry.register(changelogContents, changelogContentDir)

    val loginBonusContentDir: Path = contentDir.resolve("login-bonus")
    val loginBonusContents = com.astarworks.astera.core.application.contents.LoginBonusContents()
    val loginBonusReport = loginBonusContents.bootstrap(loginBonusContentDir)
    logger.info("Login bonus loaded: ${loginBonusReport.loaded} ok, ${loginBonusReport.failed} failed")
    services.register<com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginBonusRegistry>(loginBonusContents, priority = 0)
    reloadRegistry.register(loginBonusContents, loginBonusContentDir)

    val loginStreakRepo: com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginStreakRepository =
        services.get<com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginStreakRepository>()
            ?: com.astarworks.astera.core.application.service.loginbonus.InMemoryLoginStreakRepository().also {
                services.register<com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginStreakRepository>(it, priority = 0)
            }

    val loginBonusService: com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginBonusService =
        com.astarworks.astera.core.application.service.loginbonus.DefaultLoginBonusService(
            registry = loginBonusContents,
            streakRepo = loginStreakRepo,
            ledger = { services.get<com.astarworks.astera.core.application.port.outbound.ICurrencyLedger>() },
            events = eventBus,
        )
    services.register<com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginBonusService>(loginBonusService, priority = 0)

    // Bootstrapper for /astera admin restart — reuse the InMemoryServerBootstrapper
    // already provisioned at the lifecycle wiring block above; the local
    // `bootstrapper` val is in scope.
    commands.register(
        "admin",
        com.astarworks.astera.core.application.command.admin.buildAsteraRestartCommandSpec(
            announcer = announcementSystem,
            bootstrapper = bootstrapper,
            scheduler = scheduler,
        ),
    )
    // F1-I (ADR-0200): `/astera admin drain <target> [abort|keep]` moves this backend's players
    // to another backend so `make dev-plugins` can restart it without kicking anybody.
    commands.register(
        "admin",
        com.astarworks.astera.core.application.command.admin.buildAsteraDrainCommandSpec(
            eventBus = eventBus,
            scheduler = scheduler,
            matches = { services.get<com.astarworks.astera.core.application.port.outbound.IMatchRepository>() },
            transfers = { services.get<com.astarworks.astera.core.application.port.outbound.IServerTransferGateway>() },
            onlinePlayers = {
                services.get<com.astarworks.astera.core.application.port.outbound.admin.IAdminPlayerGateway>()
                    ?.onlinePlayers().orEmpty()
            },
        ),
    )
    // F3-9 (ADR-0224): `/astera admin pack reload [force]` re-reads the pack sha1 and re-pushes the pack.
    services.get<com.astarworks.astera.core.application.service.hud.ResourcePackService>()?.let { packService ->
        commands.register(
            "admin",
            com.astarworks.astera.core.application.command.admin.buildAsteraPackCommandSpec(packService),
        )
    }
    commands.register(
        com.astarworks.astera.core.application.command.buildAsteraNewsCommandSpec(
            registry = newsContents,
            messages = messages,
            playerGateway = playerGateway,
        ),
    )
    commands.register(
        com.astarworks.astera.core.application.command.buildAsteraPatchCommandSpec(
            registry = changelogContents,
            messages = messages,
            playerGateway = playerGateway,
        ),
    )

    logger.info(
        "Wave J 5server-content-ops wired: INewsRegistry + IChangelogRegistry " +
            "+ ILoginBonusRegistry + ILoginBonusService + ILoginStreakRepository " +
            "registered; /astera admin restart, /astera news, /astera patch commands mounted.",
    )
}

/**
 * Publishes the training range landmarks (F3-11) on backends that host the lobby. The plan is a pure
 * function of `content/worlds/lobby.yaml`, so it is known long before the lobby world is created.
 */
private fun CoreWiringState.registerLobbyTrainingRange() {
    if (!ctx.serverRole.hostsLobby) return
    val config = when (val loaded = com.astarworks.astera.core.application.service.lobby.LobbyWorldContent.load(ctx.contentDir)) {
        is com.astarworks.astera.core.domain.Result.Success -> loaded.value
        is com.astarworks.astera.core.domain.Result.Failure -> return
    }
    val plan = com.astarworks.astera.core.domain.lobby.LobbyTrainingLayout.plan(config.center.x, config.center.z, config.surfaceY)
    ctx.registrations += services.register<com.astarworks.astera.core.application.port.outbound.lobby.ILobbyTrainingRange>(
        com.astarworks.astera.core.application.port.outbound.lobby.LobbyTrainingRange(
            com.astarworks.astera.core.domain.world.WorldId(config.id),
            plan,
        ),
        priority = 0,
    )
}
