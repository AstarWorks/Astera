package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.nexus.NexusPhase
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.application.port.outbound.IMatchPhaseClock
import com.astarworks.astera.match.domain.generator.StarGenerator
import com.astarworks.astera.match.domain.star.StarSource
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase gate of the star sources (ADR-0318, ADR-0319): says at which match second a generator, and so the source it feeds,
 * opens. A wave generator opens at its `wave.opens_at_phase`; every other source is open from the start. Phase timing comes
 * from the [IMatchPhaseClock]; a match without one (a non-phase mode) is never gated.
 *
 * The answer is a point in match time (not "open now?") so [StarBudget] can integrate its token buckets exactly across the
 * opening. It is fixed the first time it is known and then stays put.
 */
public class StarGate(
    private val clock: StarSupplyClock,
    private val generators: IGeneratorRepository,
    private val phases: () -> IMatchPhaseClock?,
) {
    private val opened = ConcurrentHashMap<Pair<MatchId, Int>, Double>()

    /** Match second [generator] opens at; 0.0 when it is open from the start or the match has no phase clock. */
    public fun openAtSeconds(matchId: MatchId, generator: StarGenerator): Double {
        val phase = generator.wave?.opensAtPhase ?: 1
        return if (phase <= 1) 0.0 else openAtPhase(matchId, phase)
    }

    /** Match second [source] opens at: the earliest of its generators; 0.0 when it has none or is ungated. */
    public fun openAtSeconds(matchId: MatchId, source: StarSource): Double {
        if (source != StarSource.WAVE) return 0.0
        val waves = generators.listForMatch(matchId).filter { it.wave != null }
        if (waves.isEmpty()) return 0.0
        return waves.minOf { openAtSeconds(matchId, it) }
    }

    /** Forget a finished match. */
    public fun clear(matchId: MatchId) {
        opened.keys.removeIf { it.first == matchId }
    }

    private fun openAtPhase(matchId: MatchId, number: Int): Double {
        opened[matchId to number]?.let { return it }
        val phaseClock = phases() ?: return 0.0
        val snapshot = phaseClock.current(matchId) ?: return 0.0
        val target = NexusPhase.byNumber(number) ?: return 0.0
        val until = phaseClock.ticksUntil(matchId, target) ?: return 0.0
        val now = clock.elapsedSeconds(matchId)
        val at = when {
            until > 0L -> now + until / TICKS_PER_SECOND
            snapshot.phase.number == number -> now - (snapshot.phaseTicks - snapshot.remainingTicks) / TICKS_PER_SECOND
            else -> now
        }
        return opened.computeIfAbsent(matchId to number) { at }
    }

    private companion object {
        const val TICKS_PER_SECOND = 20.0
    }
}
