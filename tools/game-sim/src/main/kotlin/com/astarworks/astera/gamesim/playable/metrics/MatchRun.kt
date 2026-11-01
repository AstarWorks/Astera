package com.astarworks.astera.gamesim.playable.metrics

import com.astarworks.astera.gamesim.GameSim

/**
 * One headless match as [MassPlay] drives it. `playable.MatchDriver` (bot-vs-bot) implements this (ADR-0316);
 * [ScriptedAnnihilationRun] is the GameSim-only scripted variant (no brains) the metrics scenario can still use.
 */
interface MatchRun {
    val sim: GameSim

    /** The bot brain behind [player] (role / decision kind for the metrics); null for runs without brains. */
    fun brainOf(player: com.astarworks.astera.core.domain.player.PlayerId): com.astarworks.astera.bots.BotBrain? = null

    /** Advance the match [n] sim ticks; every tick calls the [onTick] listeners once, with the new tick number. */
    fun step(n: Int)

    /** True once the match has ended (MatchEnded published). */
    val ended: Boolean

    fun onTick(listener: (Long) -> Unit)
}
