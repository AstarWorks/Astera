package com.astarworks.astera.match.domain.tournament

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * One pairing within a single-elimination [Round] (Wave 7b, ADR-0037).
 *
 * A slot is `null` when the bracket size is not a power of two and the seed
 * drew a bye. A match with exactly one non-null slot is an automatic
 * advancement (bye); the bracket-advance logic (W1 `BracketAdvancer`) sets
 * [winner] to that slot. [winner] must be one of [slotA] / [slotB] when set.
 */
public data class BracketMatch(
    val slotA: PlayerId?,
    val slotB: PlayerId?,
    val winner: PlayerId? = null,
) {
    init {
        if (winner != null) {
            require(winner == slotA || winner == slotB) {
                "BracketMatch.winner must be one of its slots"
            }
        }
    }

    /** True once a winner has been resolved (by report or by bye). */
    public val isDecided: Boolean get() = winner != null

    /** True when exactly one slot is filled — the filled slot advances for free. */
    public val isBye: Boolean get() = (slotA == null) != (slotB == null)
}

/**
 * A bracket round: index 0 is the first round. A round is complete once every
 * one of its [matches] is decided.
 */
public data class Round(
    val index: Int,
    val matches: List<BracketMatch>,
) {
    init {
        require(index >= 0) { "Round.index must be >= 0" }
        require(matches.isNotEmpty()) { "Round must have at least one match" }
    }

    public val isComplete: Boolean get() = matches.all { it.isDecided }
}
