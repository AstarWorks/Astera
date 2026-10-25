package com.astarworks.astera.core.application.eventbus.listeners.presentation

import com.astarworks.astera.core.application.port.outbound.effect.ISoundCuePlayer
import com.astarworks.astera.core.application.port.outbound.music.IPlayerBgm
import com.astarworks.astera.core.domain.effect.SoundCues
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
    private val cues: ISoundCuePlayer,
    private val bgm: IPlayerBgm,
) {
    public fun onRespawned(event: PlayerRespawned) {
        cues.play(event.playerId, SoundCues.MATCH_RESPAWN)
    }

    public fun onSummary(event: MatchSummaryReady) {
        val summary = event.summary
        for (line in summary.players) {
            val outcome = summary.outcomeOf(line.playerId)
            cues.play(
                line.playerId,
                when (outcome) {
                    MatchOutcome.WIN -> SoundCues.MATCH_WIN
                    MatchOutcome.LOSE -> SoundCues.MATCH_LOSE
                    MatchOutcome.DRAW -> SoundCues.MATCH_DRAW
                },
            )
            when (outcome) {
                MatchOutcome.WIN -> bgm.switchTo(line.playerId, MusicMood.VICTORY)
                MatchOutcome.LOSE -> bgm.switchTo(line.playerId, MusicMood.DEFEAT)
                MatchOutcome.DRAW -> bgm.stop(line.playerId)
            }
        }
    }

    public fun onLoginBonus(event: LoginBonusClaimed) {
        cues.play(event.playerId, SoundCues.UI_LOGIN_BONUS)
    }

    public fun onQuestChainCompleted(event: QuestChainCompleted) {
        cues.play(event.playerId, SoundCues.UI_QUEST_COMPLETE)
    }

    public fun onCurrencyChanged(event: CurrencyChanged) {
        if (event.reason is CurrencyChangeReason.ShopPurchase) cues.play(event.playerId, SoundCues.UI_PURCHASE)
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
