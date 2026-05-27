package com.astarworks.astera.match.domain.match

import com.astarworks.astera.core.domain.match.Match

/**
 * Game-mode-aware minimum-player gate (Wave 5game-complete, ADR-0121 §F).
 *
 * Phase 1 PoC modes have different "is this match playable?" thresholds:
 *
 *  - **Norma** (2-team): 2 players minimum (1 per team).
 *  - **Annihilation** (3-team Nexus): 3 players minimum (1 per team).
 *  - **Bet 1v1** (2-team): 2 players exact (caps at 2, but uses min=2 here —
 *    the exact-2 cap is enforced elsewhere by the matchmaker).
 *
 * The policy is pure: given a [modeKey] (the string carried on `MatchStarted`
 * + `Match.score` keys) and a [Match], it returns either `Ok` (transition
 * may proceed) or `Insufficient(required, actual)` (caller must reject).
 *
 * # Where it's enforced
 *
 * Wired into `SwitchPhaseUseCase` on the COUNTDOWN→ACTIVE
 * ([com.astarworks.astera.match.domain.match.MatchStateMachine.TransitionTrigger.CountdownComplete])
 * transition. That moment is the last reversible gate: WAITING → COUNTDOWN
 * may run with 0 players (queue still filling), but COUNTDOWN→ACTIVE means
 * "we're about to start scoring" — at which point an under-populated match
 * either short-circuits to ENDED or stays in COUNTDOWN waiting for the
 * remaining slots.
 *
 * # Mode lookup
 *
 * `modeKey` is the same string used on `MatchStarted.modeKey` + the
 * `StartMatchUseCase.MODE_KEY_ANNIHILATION` companion. Unknown keys default
 * to [DEFAULT_MIN_PLAYERS] (= Norma's 2-player floor). Phase 4 mode
 * additions register their floor in [requirementFor]; until then this is a
 * deliberately tiny lookup so contributor mental model stays trivial.
 *
 * # Why not a port?
 *
 * Pure value-object policy. No I/O, no async, no Bukkit. Ports are for
 * things the domain doesn't know how to do — counting players is a `for`
 * loop over [Match.teams]. Keeping this in the domain lets every unit test
 * exercise it without DI.
 */
public object MatchMinPlayerPolicy {

    /** Sealed outcome of a [check] call. */
    public sealed interface Outcome {
        /** Player count meets the [modeKey]'s minimum. */
        public data object Ok : Outcome

        /**
         * Player count below the minimum. [required] = mode's floor;
         * [actual] = current participant count across all teams.
         */
        public data class Insufficient(val required: Int, val actual: Int) : Outcome
    }

    /**
     * Minimum total participants the [modeKey] needs to legally start.
     * Unknown keys default to [DEFAULT_MIN_PLAYERS].
     */
    public fun requirementFor(modeKey: String?): Int = when (modeKey) {
        MODE_KEY_ANNIHILATION -> ANNIHILATION_MIN_PLAYERS
        MODE_KEY_BET_1V1 -> BET_1V1_MIN_PLAYERS
        // Includes `null` (legacy Norma) + any unrecognised key.
        else -> DEFAULT_MIN_PLAYERS
    }

    /** Apply the mode's min-player floor against [match]'s current roster. */
    public fun check(modeKey: String?, match: Match): Outcome {
        val required = requirementFor(modeKey)
        val actual = match.teams.sumOf { it.members.size }
        return if (actual >= required) Outcome.Ok else Outcome.Insufficient(required, actual)
    }

    /** Phase 1 PoC mode keys. Mirrored on [com.astarworks.astera.match.application.usecase.match.StartMatchUseCase]. */
    public const val MODE_KEY_ANNIHILATION: String = "annihilation"
    public const val MODE_KEY_BET_1V1: String = "bet-1v1"

    /** Norma + every unknown mode: 1 per team in a 2-team match. */
    public const val DEFAULT_MIN_PLAYERS: Int = 2

    /** Annihilation: 1 per team across the 3 nexus teams (red / blue / yellow). */
    public const val ANNIHILATION_MIN_PLAYERS: Int = 3

    /** Bet 1v1: exact 2 players (matchmaker caps; this guard is the floor). */
    public const val BET_1V1_MIN_PLAYERS: Int = 2
}
