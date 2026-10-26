package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.eventbus.listeners.presentation.MilestonePresentationListener
import com.astarworks.astera.core.application.port.outbound.effect.IPlayerSoundGateway
import com.astarworks.astera.core.application.port.outbound.effect.ISoundCuePlayer
import com.astarworks.astera.core.application.port.outbound.effect.ISoundRegistry
import com.astarworks.astera.core.application.port.outbound.music.IMusicGateway
import com.astarworks.astera.core.application.port.outbound.music.IMusicTrackRegistry
import com.astarworks.astera.core.application.port.outbound.music.IPlayerBgm
import com.astarworks.astera.core.application.service.effect.SoundCuePlayer
import com.astarworks.astera.core.application.service.music.PlayerBgmController
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentationTable
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentationVisuals
import com.astarworks.astera.core.application.service.presentation.LoggingPresentationVisuals
import com.astarworks.astera.core.application.service.presentation.PresentationPlayer
import com.astarworks.astera.core.application.service.presentation.PresentationTable
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.services.register
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.event.LoginBonusClaimed
import com.astarworks.astera.core.domain.event.MatchSummaryReady
import com.astarworks.astera.core.domain.event.PlayerEnteredMatch
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.core.domain.event.PlayerReturnedToLobby
import com.astarworks.astera.core.domain.event.QuestChainCompleted

/**
 * ADR-0267: the one sound path (`ISoundCuePlayer`), the BGM selector (`IPlayerBgm`) and the
 * milestone listeners whose events live in Core. Runs after every gateway has been registered.
 */
internal fun CoreWiringState.wirePresentation() {
    val cues = SoundCuePlayer(
        registry = services.getOrThrow<ISoundRegistry>(),
        gateway = { services.getOrThrow<IPlayerSoundGateway>() },
        soundVolumePercent = { settingsRepo.findOrDefault(it).soundVolume },
    )
    ctx.registrations += services.register<ISoundCuePlayer>(cues, priority = 0)
    // FX-05: one table (content/presentation/events.yaml) decides every moment's cue / title / particles.
    val table = PresentationTable.load(contentDir.resolve("presentation").resolve("events.yaml"))
    ctx.registrations += services.register<IPresentationTable>(table, priority = 0)
    ctx.registrations += services.register<IPresentationVisuals>(LoggingPresentationVisuals(), priority = 0)
    ports.presentationVisuals?.let { ctx.registrations += services.register<IPresentationVisuals>(it(services), priority = 100) }
    val presentation = PresentationPlayer(
        table = table,
        cues = cues,
        visuals = { services.getOrThrow<IPresentationVisuals>() },
        visibilityOf = { settingsRepo.findOrDefault(it).particleVisibility },
        sameTeam = { a, b ->
            services.get<IMatchRepository>()?.findMatchOf(a)?.teams?.any { a in it.members && b in it.members } == true
        },
    )
    ctx.registrations += services.register<IPresentation>(presentation, priority = 0)
    logger.info("Presentation table: ${table.moments().size} moments, ${table.presets().size} particle presets")
    val bgm = PlayerBgmController(
        tracks = services.getOrThrow<IMusicTrackRegistry>(),
        gateway = { services.getOrThrow<IMusicGateway>() },
        musicVolumePercent = { settingsRepo.findOrDefault(it).musicVolume },
    )
    ctx.registrations += services.register<IPlayerBgm>(bgm, priority = 0)

    val l = MilestonePresentationListener(presentation, bgm)
    val subs = ctx.subscriptions
    subs += eventBus.subscribe(PlayerRespawned::class) { l.onRespawned(it) }
    subs += eventBus.subscribe(MatchSummaryReady::class) { l.onSummary(it) }
    subs += eventBus.subscribe(LoginBonusClaimed::class) { l.onLoginBonus(it) }
    subs += eventBus.subscribe(QuestChainCompleted::class) { l.onQuestChainCompleted(it) }
    subs += eventBus.subscribe(CurrencyChanged::class) { l.onCurrencyChanged(it) }
    subs += eventBus.subscribe(PlayerJoinedServer::class) { l.onJoined(it) }
    subs += eventBus.subscribe(com.astarworks.astera.core.domain.event.ResourcePackStatusChanged::class) { l.onPackStatus(it) }
    subs += eventBus.subscribe(PlayerReturnedToLobby::class) { l.onReturnedToLobby(it) }
    subs += eventBus.subscribe(PlayerEnteredMatch::class) { l.onEnteredMatch(it) }
    subs += eventBus.subscribe(PlayerQuitServer::class) { l.onQuit(it) }
    logger.info("ADR-0267 presentation wired: ISoundCuePlayer / IPlayerBgm / milestone listeners")
}
