package com.astarworks.astera.match.application.eventbus.listeners.match

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.core.application.port.outbound.music.IPlayerBgm
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.music.MusicMood
import com.astarworks.astera.match.domain.event.PhaseChanged

/**
 * Sound cues and BGM tension for the match milestones owned by the match plugin (ADR-0267):
 * the 5-4-3-2-1 countdown ticks and the "go" cue, the victim's death cue, and the switch to the
 * high-intensity track when the match enters its last stretch (sudden death).
 *
 * The countdown timing mirrors [MatchCountdownListener] (one tick sound per second, "go" when the
 * last second ends); the kill cue for the killer is the hit-feedback listener's.
 */
public class MatchCueListener(
    private val matches: IMatchRepository,
    private val presentation: IPresentation,
    private val bgm: IPlayerBgm,
    private val scheduler: IScheduler,
) {

    public fun onStarted(event: MatchStarted) {
        val match = matches.findById(event.matchId) ?: return
        val recipients = match.teams.flatMap { it.members }
        if (recipients.isEmpty()) return
        for (n in MatchCountdownListener.COUNTDOWN_FROM downTo 1) {
            val offset = (MatchCountdownListener.COUNTDOWN_FROM - n).toLong() * MatchCountdownListener.TICKS_PER_SECOND
            cueAt(recipients, offset, PresentationMoments.COUNTDOWN_TICK)
        }
        val goAt = MatchCountdownListener.COUNTDOWN_FROM.toLong() * MatchCountdownListener.TICKS_PER_SECOND
        cueAt(recipients, goAt, PresentationMoments.COUNTDOWN_GO)
    }

    /** Each cue plays on its listener's own thread. */
    private fun cueAt(recipients: List<PlayerId>, delayTicks: Long, moment: String) {
        for (player in recipients) {
            scheduler.run(ExecutionScope.Entity(player.uuid), ticks(delayTicks)) { presentation.play(moment, player) }
        }
    }

    public fun onKilled(event: PlayerKilled) {
        presentation.play(PresentationMoments.DEATH, event.victim)
    }

    public fun onPhaseChanged(event: PhaseChanged) {
        if (event.to != MatchPhase.SUDDEN_DEATH) return
        val match = matches.findById(event.matchId) ?: return
        for (member in match.teams.flatMap { it.members }) {
            presentation.play(PresentationMoments.SUDDEN_DEATH, member)
            bgm.switchTo(member, MusicMood.HIGH_INTENSITY)
        }
    }
}
