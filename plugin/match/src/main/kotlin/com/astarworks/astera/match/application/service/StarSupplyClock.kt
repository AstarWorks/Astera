package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.star.StarSupply
import java.util.concurrent.ConcurrentHashMap

/**
 * When each match went active, and the [StarSupply] formula of that match (layer defaults + mode overrides): the one
 * place the star pace asks "how far into the match are we, and what is the supply multiplier now".
 */
public class StarSupplyClock(
    private val scheduler: IScheduler,
    private val supplyOf: (MatchId) -> StarSupply,
) {
    private val startTicks: MutableMap<MatchId, Long> = ConcurrentHashMap()

    public fun onStarted(matchId: MatchId) {
        startTicks[matchId] = scheduler.currentTick()
    }

    public fun onEnded(matchId: MatchId) {
        startTicks.remove(matchId)
    }

    /** Seconds since the match went active (a match first seen mid-flight starts its clock now). */
    public fun elapsedSeconds(matchId: MatchId): Double {
        val start = startTicks.computeIfAbsent(matchId) { scheduler.currentTick() }
        return (scheduler.currentTick() - start) / TICKS_PER_SECOND
    }

    /** The supply multiplier of [matchId] right now. */
    public fun multiplier(matchId: MatchId): Double = supplyOf(matchId).multiplierAt(elapsedSeconds(matchId))

    /** Stars of a pop / wave of authored size [baseAmount] right now. */
    public fun amount(matchId: MatchId, baseAmount: Int): Int = supplyOf(matchId).amountAt(baseAmount, elapsedSeconds(matchId))

    /** Stars of a pop / wave of authored size [baseAmount] at [seconds] into the match (for announcing a coming wave). */
    public fun amountAtSeconds(matchId: MatchId, baseAmount: Int, seconds: Double): Int = supplyOf(matchId).amountAt(baseAmount, seconds)

    private companion object {
        const val TICKS_PER_SECOND = 20.0
    }
}
