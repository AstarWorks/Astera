package com.astarworks.astera.match.domain.gamemode.annihilation

import com.astarworks.astera.core.domain.nexus.NexusPhase

/**
 * Pure decision logic for Annihilation phase transitions (ADR-0108,
 * Wave 5annihilation).
 *
 * Stateless — every method takes the [AnnihilationMatchState] and the
 * current tick + returns the next phase to enter (or null = stay put). The
 * tracker calls [evaluate] every `IGameMode.onTick` and acts on a non-null
 * return.
 *
 * ## Transition rules
 *
 *  - **[NexusPhase.Phase1Build] → [NexusPhase.Phase2Engage]** when:
 *      `currentTick - phaseEnteredAtTick >= config.buildPhaseTicks`
 *    Time-driven only — no early exit.
 *  - **[NexusPhase.Phase2Engage] → [NexusPhase.Phase3Tier3]** when:
 *      any team has lost both Tier 1 + Tier 2 pillars, OR
 *      `currentTick - phaseEnteredAtTick >= config.engagePhaseTicks`
 *    Whichever fires first.
 *  - **[NexusPhase.Phase3Tier3] → [NexusPhase.Phase4SuddenDeath]** when:
 *      `currentTick - phaseEnteredAtTick >= config.tier3PhaseTicks`, AND
 *      `survivingTeams.size > 1` (if 1 left, the match ends instead).
 *  - **[NexusPhase.Phase4SuddenDeath]** is terminal — the tracker reads
 *    "surviving teams <= 1 OR currentTick - phaseEnteredAtTick >=
 *    suddenDeathTicks" and ends the match.
 *
 * Win-condition evaluation is a separate concern — see [WinConditionRules].
 *
 * ## Why a separate rules class (not methods on AnnihilationMatchState)?
 *
 * Two callers want this logic without mutating state:
 *
 *  - The tracker (decides whether to call `enterPhase`).
 *  - The HUD overlay (renders "build phase ends in 1:23").
 *
 * Hanging the logic on the state object would mix decision logic with
 * mutation. Keeping it stateless mirrors the established Wave 5c
 * `MatchStateMachine` split (state class + rules class).
 */
public object AnnihilationPhaseRules {

    /**
     * Decide whether [state] should advance now. Returns the new phase or
     * null if no transition fires.
     */
    public fun evaluate(state: AnnihilationMatchState, currentTick: Long): NexusPhase? {
        val elapsed = currentTick - state.phaseEnteredAtTick
        return when (state.phase) {
            NexusPhase.Phase1Build -> {
                if (elapsed >= state.config.buildPhaseTicks) NexusPhase.Phase2Engage else null
            }
            NexusPhase.Phase2Engage -> {
                val anyLowerCascade = state.allTeams.any { state.hasLostBothLowerTiers(it) }
                if (anyLowerCascade || elapsed >= state.config.engagePhaseTicks) {
                    NexusPhase.Phase3Tier3
                } else {
                    null
                }
            }
            NexusPhase.Phase3Tier3 -> {
                if (state.survivingTeams.size <= 1) {
                    // Win condition decides match end — phase doesn't advance.
                    null
                } else if (elapsed >= state.config.tier3PhaseTicks) {
                    NexusPhase.Phase4SuddenDeath
                } else {
                    null
                }
            }
            NexusPhase.Phase4SuddenDeath -> null
        }
    }

    /**
     * Ticks remaining until the next time-driven transition fires for the
     * current phase. Returns null for [NexusPhase.Phase4SuddenDeath] (its
     * timer expiry triggers the match-end win condition, not a phase advance).
     *
     * Useful for the HUD overlay's countdown bar.
     */
    public fun ticksUntilNextPhase(state: AnnihilationMatchState, currentTick: Long): Long? {
        val elapsed = currentTick - state.phaseEnteredAtTick
        val deadline = when (state.phase) {
            NexusPhase.Phase1Build -> state.config.buildPhaseTicks
            NexusPhase.Phase2Engage -> state.config.engagePhaseTicks
            NexusPhase.Phase3Tier3 -> state.config.tier3PhaseTicks
            NexusPhase.Phase4SuddenDeath -> return null
        }
        return (deadline - elapsed).coerceAtLeast(0L)
    }
}
