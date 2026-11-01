package com.astarworks.astera.gamesim.playable.metrics

import com.astarworks.astera.gamesim.GameSim

/**
 * One headless match as [MassPlay] drives it. `playable.MatchDriver` (bot-vs-bot) implements this at merge
 * (ADR-0316); [ScriptedAnnihilationRun] is the GameSim-only stand-in until then.
 */
interface MatchRun {
    val sim: GameSim

    /** Advance the match [n] sim ticks; every tick calls the [onTick] listeners once, with the new tick number. */
    fun step(n: Int)

    /** True once the match has ended (MatchEnded published). */
    val ended: Boolean

    fun onTick(listener: (Long) -> Unit)
}
