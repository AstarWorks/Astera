package com.astarworks.astera.match.application.gamemode.annihilation

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.match.application.port.outbound.IMatchPhaseClock
import com.astarworks.astera.match.application.port.outbound.MatchPhaseSnapshot
import com.astarworks.astera.match.domain.gamemode.annihilation.AnnihilationPhaseRules

/** [IMatchPhaseClock] of Annihilation: reads the tracker's phase and entry tick against [now] (ADR-0319). */
public class AnnihilationPhaseClock(
    private val tracker: AnnihilationMatchTracker,
    private val now: () -> Long,
) : IMatchPhaseClock {

    override fun current(matchId: MatchId): MatchPhaseSnapshot? {
        val state = tracker.state(matchId) ?: return null
        val tick = now()
        val remaining = AnnihilationPhaseRules.ticksUntilNextPhase(state, tick)
            ?: AnnihilationPhaseRules.ticksUntilMatchEnd(state, tick)
            ?: 0L
        return MatchPhaseSnapshot(state.phase, remaining, state.config.ticksOf(state.phase))
    }

    override fun ticksUntil(matchId: MatchId, target: NexusPhase): Long? {
        val state = tracker.state(matchId) ?: return null
        if (target.number <= state.phase.number) return 0L
        var ticks = AnnihilationPhaseRules.ticksUntilNextPhase(state, now()) ?: return 0L
        var phase = NexusPhase.next(state.phase)
        while (phase != null && phase.number < target.number) {
            ticks += state.config.ticksOf(phase)
            phase = NexusPhase.next(phase)
        }
        return ticks
    }
}
