package com.astarworks.astera.core.application.eventbus.listeners.presentation

import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.core.application.port.outbound.music.IPlayerBgm
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.event.LoginBonusClaimed
import com.astarworks.astera.core.domain.event.MatchSummaryReady
import com.astarworks.astera.core.domain.event.PlayerEnteredMatch
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.core.domain.event.PlayerReturnedToLobby
import com.astarworks.astera.core.domain.event.QuestChainCompleted
import com.astarworks.astera.core.domain.match.result.MatchOutcome
import com.astarworks.astera.core.domain.music.MusicMood

/**
 * Sound cues and BGM for the milestones whose event lives in Core (ADR-0256): respawn, match
 * result, login bonus, quest chain complete, shop purchase; BGM follows the player between lobby,
 * match and the result sting. Match-plugin milestones (countdown, go, death) are in
 * `MatchCueListener`.
 */
public class MilestonePresentationListener(
    private val presentation: IPresentation,
    private val bgm: IPlayerBgm,
) {
    public fun onRespawned(event: PlayerRespawned) {
        presentation.play(PresentationMoments.RESPAWN, event.playerId)
    }

    public fun onSummary(event: MatchSummaryReady) {
        val summary = event.summary
        for (line in summary.players) {
            val outcome = summary.outcomeOf(line.playerId)
            presentation.play(
                when (outcome) {
                    MatchOutcome.WIN -> PresentationMoments.MATCH_WIN
                    MatchOutcome.LOSE -> PresentationMoments.MATCH_LOSE
                    MatchOutcome.DRAW -> PresentationMoments.MATCH_DRAW
                },
                line.playerId,
            )
            when (outcome) {
                MatchOutcome.WIN -> bgm.switchTo(line.playerId, MusicMood.VICTORY)
                MatchOutcome.LOSE -> bgm.switchTo(line.playerId, MusicMood.DEFEAT)
                MatchOutcome.DRAW -> bgm.stop(line.playerId)
            }
        }
    }

    public fun onLoginBonus(event: LoginBonusClaimed) {
        presentation.play(PresentationMoments.REWARD_LOGIN, event.playerId)
    }

    public fun onQuestChainCompleted(event: QuestChainCompleted) {
        presentation.play(PresentationMoments.QUEST_COMPLETE, event.playerId)
    }

    public fun onCurrencyChanged(event: CurrencyChanged) {
        if (event.reason is CurrencyChangeReason.ShopPurchase) presentation.play(PresentationMoments.PURCHASE, event.playerId)
    }

    public fun onJoined(event: PlayerJoinedServer) {
        bgm.switchTo(event.playerId, MusicMood.LOBBY)
    }

    public fun onReturnedToLobby(event: PlayerReturnedToLobby) {
        bgm.switchTo(event.playerId, MusicMood.LOBBY)
    }

    public fun onEnteredMatch(event: PlayerEnteredMatch) {
        bgm.switchTo(event.playerId, MusicMood.MATCH)
    }

    public fun onQuit(event: PlayerQuitServer) {
        bgm.stop(event.playerId)
    }
}
