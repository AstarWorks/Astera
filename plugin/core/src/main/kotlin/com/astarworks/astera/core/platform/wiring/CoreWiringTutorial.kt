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
import com.astarworks.astera.core.domain.skill.SkillTrigger

internal fun CoreWiringState.wireTutorialGuide() {
    if (!ctx.serverRole.hostsLobby) return
    val lobby = LobbyWorldContent.load(contentDir).getOrNull() ?: return
    val npcs = mutableListOf<LobbyNpcYamlConfig>()
    ContentLoader(ContentLoader.DEFAULT_PARSERS, LobbyNpcYamlConfig.serializer())
        .loadFrom(contentDir.resolve("lobby-npcs")) { npcs += it }
    fun npcById(id: String): Vec3? = npcs.firstOrNull { it.id == id }?.spawn?.let { Vec3(it.x, it.y, it.z) }
    fun npc(intent: String): Vec3? = npcs.firstOrNull { it.interaction.rightClickIntent == intent }
        ?.spawn?.let { Vec3(it.x, it.y, it.z) }
    val guide = TutorialGuide(
        tutorialSequencer, mcServer, scheduler, services.getOrThrow<IHologramGateway>(),
        range = { services.get<ILobbyTrainingRange>() }, eligible = { tutorialEligible(it) },
        landmark = { action -> when (action) {
            "menu" -> Vec3(lobby.spawn.x, lobby.spawn.y, lobby.spawn.z)
            "shop" -> npc("lobby.shop.open")
            "vault" -> npc("lobby.customize.show")
            // ADR-0248: the marker points at the Play gate in front of the spawn, not at a far lane pad.
            "queue" -> npcById("play-npc") ?: services.get<LobbyLanePadService>()?.pads()?.values?.firstOrNull()
            else -> null
        } },
    )
    ctx.registrations += services.register<TutorialGuide>(guide)
    ctx.subscriptions += eventBus.subscribe(PlayerJoinedServer::class) { guide.joined(it.playerId) }
    ctx.subscriptions += eventBus.subscribe(PlayerQuitServer::class) { guide.left(it.playerId) }
    ctx.subscriptions += eventBus.subscribe(MatchFormed::class) { it.players.forEach(guide::hide) }
    ctx.subscriptions += eventBus.subscribe(MatchStarted::class) {
        services.get<IMatchRepository>()?.findById(it.matchId)?.teams?.flatMap { team -> team.members }?.forEach(guide::hide)
    }
    ctx.subscriptions += eventBus.subscribe(TutorialStepEntered::class) { guide.refresh(it.player) }
    ctx.subscriptions += eventBus.subscribe(TutorialSequenceCancelled::class) { guide.hide(it.playerId) }
    ctx.subscriptions += eventBus.subscribe(TutorialSequenceCompleted::class) {
        guide.hide(it.playerId)
        mcServer.findPlayer(it.playerId)?.sendTitle(
            com.astarworks.astera.core.domain.i18n.MessageKey("astera.tutorial.guide.complete.title"),
            com.astarworks.astera.core.domain.i18n.MessageKey("astera.tutorial.guide.complete.subtitle"),
        )
    }
    ctx.subscriptions += eventBus.subscribe(SkillCooldownStarted::class) {
        if (it.sourceItemId?.value == "knight-sword" && it.trigger == SkillTrigger.SNEAK_RIGHT && it.skillId.value == "sword-dash") {
            tutorialSequencer.recordAction(it.playerId, "secondary")
        }
    }
    ctx.subscriptions += eventBus.subscribe(ResourceReloaded::class) {
        if (it.weapon.value == "pistol") tutorialSequencer.recordAction(it.playerId, "reload")
    }
    ctx.subscriptions += eventBus.subscribe(LobbyLanePadEntered::class) {
        tutorialSequencer.recordAction(it.playerId, "queue")
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
