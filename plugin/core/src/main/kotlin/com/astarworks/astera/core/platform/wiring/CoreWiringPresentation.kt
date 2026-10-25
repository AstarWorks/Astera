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
 * ADR-0256: the one sound path (`ISoundCuePlayer`), the BGM selector (`IPlayerBgm`) and the
 * milestone listeners whose events live in Core. Runs after every gateway has been registered.
 */
internal fun CoreWiringState.wirePresentation() {
    val cues = SoundCuePlayer(
        registry = services.getOrThrow<ISoundRegistry>(),
        gateway = { services.getOrThrow<IPlayerSoundGateway>() },
        soundVolumePercent = { settingsRepo.findOrDefault(it).soundVolume },
    )
    ctx.registrations += services.register<ISoundCuePlayer>(cues, priority = 0)
    val bgm = PlayerBgmController(
        tracks = services.getOrThrow<IMusicTrackRegistry>(),
        gateway = { services.getOrThrow<IMusicGateway>() },
        musicVolumePercent = { settingsRepo.findOrDefault(it).musicVolume },
    )
    ctx.registrations += services.register<IPlayerBgm>(bgm, priority = 0)

    val l = MilestonePresentationListener(cues, bgm)
    val subs = ctx.subscriptions
    subs += eventBus.subscribe(PlayerRespawned::class) { l.onRespawned(it) }
    subs += eventBus.subscribe(MatchSummaryReady::class) { l.onSummary(it) }
    subs += eventBus.subscribe(LoginBonusClaimed::class) { l.onLoginBonus(it) }
    subs += eventBus.subscribe(QuestChainCompleted::class) { l.onQuestChainCompleted(it) }
    subs += eventBus.subscribe(CurrencyChanged::class) { l.onCurrencyChanged(it) }
    subs += eventBus.subscribe(PlayerJoinedServer::class) { l.onJoined(it) }
    subs += eventBus.subscribe(PlayerReturnedToLobby::class) { l.onReturnedToLobby(it) }
    subs += eventBus.subscribe(PlayerEnteredMatch::class) { l.onEnteredMatch(it) }
    subs += eventBus.subscribe(PlayerQuitServer::class) { l.onQuit(it) }
    logger.info("ADR-0256 presentation wired: ISoundCuePlayer / IPlayerBgm / milestone listeners")
}
