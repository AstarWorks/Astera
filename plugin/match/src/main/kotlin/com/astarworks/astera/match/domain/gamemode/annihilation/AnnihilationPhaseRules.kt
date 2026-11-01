package com.astarworks.astera.match.domain.gamemode.annihilation

import com.astarworks.astera.core.domain.nexus.NexusPhase

/**
 * Pure decision logic for Annihilation phase transitions (ADR-0108, reshaped by ADR-0319).
 *
 * Stateless. Every phase but the last ends when `currentTick - phaseEnteredAtTick` reaches that phase's configured
 * length ([AnnihilationConfig.ticksOf]); the order is [NexusPhase.ordered]. There is no early exit: the Mid ore gates
 * and the HUD countdowns depend on the clock being the only driver. [NexusPhase.Phase5SuddenDeath] is terminal; its
 * expiry is a win condition (HP tiebreak, see [WinConditionRules]), not a phase advance.
 */
public object AnnihilationPhaseRules {

    /** The phase [state] should advance to now, or null while the current phase still runs. */
    public fun evaluate(state: AnnihilationMatchState, currentTick: Long): NexusPhase? {
        val next = NexusPhase.next(state.phase) ?: return null
        val elapsed = currentTick - state.phaseEnteredAtTick
        return if (elapsed >= state.config.ticksOf(state.phase)) next else null
    }

    /** Ticks until the current phase ends (0 once due); null in [NexusPhase.Phase5SuddenDeath]. Drives the HUD countdowns. */
    public fun ticksUntilNextPhase(state: AnnihilationMatchState, currentTick: Long): Long? {
        if (NexusPhase.next(state.phase) == null) return null
        val elapsed = currentTick - state.phaseEnteredAtTick
        return (state.config.ticksOf(state.phase) - elapsed).coerceAtLeast(0L)
    }

    /** Ticks until the match ends by timeout while in [NexusPhase.Phase5SuddenDeath]; null in earlier phases. */
    public fun ticksUntilMatchEnd(state: AnnihilationMatchState, currentTick: Long): Long? {
        if (state.phase != NexusPhase.Phase5SuddenDeath) return null
        return (state.config.suddenDeathTicks - (currentTick - state.phaseEnteredAtTick)).coerceAtLeast(0L)
    }
}
