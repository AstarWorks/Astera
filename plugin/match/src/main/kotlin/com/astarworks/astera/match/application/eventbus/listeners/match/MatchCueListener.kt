package com.astarworks.astera.match.application.eventbus.listeners.match

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.effect.ISoundCuePlayer
import com.astarworks.astera.core.application.port.outbound.music.IPlayerBgm
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.effect.SoundCues
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.music.MusicMood
import com.astarworks.astera.match.domain.event.PhaseChanged

/**
 * Sound cues and BGM tension for the match milestones owned by the match plugin (ADR-0256):
 * the 5-4-3-2-1 countdown ticks and the "go" cue, the victim's death cue, and the switch to the
 * high-intensity track when the match enters its last stretch (sudden death).
 *
 * The countdown timing mirrors [MatchCountdownListener] (one tick sound per second, "go" when the
 * last second ends); the kill cue for the killer is the hit-feedback listener's.
 */
public class MatchCueListener(
    private val matches: IMatchRepository,
    private val cues: ISoundCuePlayer,
    private val bgm: IPlayerBgm,
    private val scheduler: IScheduler,
) {

    public fun onStarted(event: MatchStarted) {
        val match = matches.findById(event.matchId) ?: return
        val recipients = match.teams.flatMap { it.members }
        if (recipients.isEmpty()) return
        for (n in MatchCountdownListener.COUNTDOWN_FROM downTo 1) {
            val offset = (MatchCountdownListener.COUNTDOWN_FROM - n).toLong() * MatchCountdownListener.TICKS_PER_SECOND
            scheduler.runLater(ticks(offset)) { cues.playAll(recipients, SoundCues.MATCH_COUNTDOWN_TICK) }
        }
        val goAt = MatchCountdownListener.COUNTDOWN_FROM.toLong() * MatchCountdownListener.TICKS_PER_SECOND
        scheduler.runLater(ticks(goAt)) { cues.playAll(recipients, SoundCues.MATCH_GO) }
    }

    public fun onKilled(event: PlayerKilled) {
        cues.play(event.victim, SoundCues.MATCH_DEATH)
    }

    public fun onPhaseChanged(event: PhaseChanged) {
        if (event.to != MatchPhase.SUDDEN_DEATH) return
        val match = matches.findById(event.matchId) ?: return
        for (member in match.teams.flatMap { it.members }) bgm.switchTo(member, MusicMood.HIGH_INTENSITY)
    }
}
