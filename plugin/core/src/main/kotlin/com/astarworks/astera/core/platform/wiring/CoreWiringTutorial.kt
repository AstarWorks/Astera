package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.config.LobbyNpcYamlConfig
import com.astarworks.astera.core.application.port.outbound.*
import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyTrainingRange
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.application.service.lobby.LobbyLanePadService
import com.astarworks.astera.core.application.service.lobby.LobbyWorldContent
import com.astarworks.astera.core.application.service.tutorial.TutorialGuide
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.event.*
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.core.domain.presentation.PresentationMoments

internal fun CoreWiringState.wireTutorialGuide() {
    if (!ctx.serverRole.hostsLobby) return
    val lobby = LobbyWorldContent.load(contentDir).getOrNull() ?: return
    val npcs = mutableListOf<LobbyNpcYamlConfig>()
    ContentLoader(ContentLoader.DEFAULT_PARSERS, LobbyNpcYamlConfig.serializer())
        .loadFrom(contentDir.resolve("lobby-npcs")) { npcs += it }
    fun npcById(id: String): Vec3? = npcs.firstOrNull { it.id == id }?.spawn?.let { Vec3(it.x, it.y, it.z) }
    val guide = TutorialGuide(
        tutorialSequencer, mcServer, scheduler, services.getOrThrow<IHologramGateway>(),
        range = { services.get<ILobbyTrainingRange>() }, eligible = { tutorialEligible(it) },
        hintsEnabled = { services.get<com.astarworks.astera.core.application.service.hud.HudPreferences>()?.tutorialHintsEnabled(it) ?: true },
        landmark = { action -> when (action) {
            // Menu-driven steps have no NPC: the bubble floats over the spawn tile, where the menu key is pressed.
            "menu", "result", "equip", "missions" -> Vec3(lobby.spawn.x, lobby.spawn.y, lobby.spawn.z)
            // ADR-0248: the marker points at the Play gate in front of the spawn, not at a far lane pad.
            "queue", "match" -> npcById("play-npc") ?: services.get<LobbyLanePadService>()?.pads()?.values?.firstOrNull()
            "shop" -> npcById("market-npc")
            else -> null
        } },
        render = { player, key -> services.get<IMessageRenderer>()?.render(player, key) ?: key.value },
        onStepDone = { player -> services.get<IPresentation>()?.play(PresentationMoments.QUEST_COMPLETE, player) },
    )
    ctx.registrations += services.register<TutorialGuide>(guide)
    ctx.subscriptions += eventBus.subscribe(PlayerJoinedServer::class) { guide.joined(it.playerId) }
    ctx.subscriptions += eventBus.subscribe(PlayerQuitServer::class) { guide.left(it.playerId) }
    ctx.subscriptions += eventBus.subscribe(MatchFormed::class) { it.players.forEach(guide::hide) }
    ctx.subscriptions += eventBus.subscribe(MatchStarted::class) {
        services.get<IMatchRepository>()?.findById(it.matchId)?.teams?.flatMap { team -> team.members }?.forEach(guide::hide)
    }
    ctx.subscriptions += eventBus.subscribe(TutorialStepEntered::class) { guide.refresh(it.player, it.stepIndex) }
    // UI-11: finishing a match happens in the match (presentation suspended), so it is applied once the player is back.
    ctx.subscriptions += eventBus.subscribe(MatchEnded::class) {
        services.get<IMatchRepository>()?.findById(it.matchId)?.teams?.flatMap { team -> team.members }?.forEach { p -> guide.note(p, "match") }
    }
    ctx.subscriptions += eventBus.subscribe(PlayerReturnedToLobby::class) { guide.note(it.playerId, "match") }
    ctx.subscriptions += eventBus.subscribe(CurrencyChanged::class) {
        if (it.reason is CurrencyChangeReason.ShopPurchase && it.delta < 0) tutorialSequencer.recordAction(it.playerId, "shop")
    }
    // The first weapon the player selects after the shop step ("equip"): hotbar selection raises the active-weapon event.
    ctx.subscriptions += eventBus.subscribe(PlayerActiveWeaponChanged::class) {
        if (it.current != null) tutorialSequencer.recordAction(it.playerId, "equip")
    }
    ctx.subscriptions += eventBus.subscribe(TutorialSequenceCancelled::class) { guide.hide(it.playerId) }
    ctx.subscriptions += eventBus.subscribe(TutorialSequenceCompleted::class) {
        guide.hide(it.playerId)
        services.get<IPresentation>()?.play(PresentationMoments.QUEST_COMPLETE, it.playerId)
        mcServer.findPlayer(it.playerId)?.sendTitle(
            com.astarworks.astera.core.domain.i18n.MessageKey("astera.tutorial.guide.complete.title"),
            com.astarworks.astera.core.domain.i18n.MessageKey("astera.tutorial.guide.complete.subtitle"),
        )
    }
    ctx.subscriptions += eventBus.subscribe(LobbyLanePadEntered::class) {
        tutorialSequencer.recordAction(it.playerId, "queue")
    }
    // Menu tiles the guide teaches (UI-11): last result and Daily/Quests. Observed on the router so no handler changes.
    guiIntentObserver = { player, intent ->
        when (intent.key) {
            "stats.last" -> tutorialSequencer.recordAction(player.id, "result")
            "progress.open" -> tutorialSequencer.recordAction(player.id, "missions")
        }
    }
    // Queueing from the Play picker counts too (ADR-0248).
    ctx.subscriptions += eventBus.subscribe(MatchQueueJoined::class) {
        tutorialSequencer.recordAction(it.playerId, "queue")
    }
    val task = scheduler.runRepeatingTicks(ExecutionScope.Global, 10, 10) { guide.tick() }
    shutdownHooks += { task.cancel(); guide.close() }
}

/** Enrollment can be saved before a join teleport, but gameplay and presentation only run here. */
internal fun CoreWiringState.tutorialEligible(player: PlayerId): Boolean {
    if (!ctx.serverRole.hostsLobby || mcServer.findPlayer(player) == null) return false
    val training = services.get<ILobbyTrainingRange>() ?: return false
    val pose = services.get<IPlayerPoseLookup>()?.poseOf(player) ?: return false
    return pose.world == training.world && !tutorialInMatch(player)
}

internal fun CoreWiringState.tutorialInMatch(player: PlayerId): Boolean =
    com.astarworks.astera.core.domain.lobby.LobbyMenuKitPolicy.inMatch(
        snapshotHeld = services.get<com.astarworks.astera.core.application.eventbus.listeners.MatchInventorySnapshotListener>()?.isHeld(player) ?: false,
        membershipPhase = services.get<IMatchRepository>()?.findMatchOf(player)?.phase,
    )
