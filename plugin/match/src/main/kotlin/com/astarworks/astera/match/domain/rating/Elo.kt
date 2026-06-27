package com.astarworks.astera.match.domain.rating

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Standard Elo rating math (Wave 7b, ADR-0037).
 *
 * Pure functions over [Rating] value objects — no I/O, no clock, no Bukkit.
 * Results are clamped through [Rating.of] so a single update can never push a
 * value past `[Rating.MIN, Rating.MAX]`.
 *
 * The expected score of a player against an opponent is
 * `1 / (1 + 10^((opp - self) / 400))`; a win/loss moves the rating by
 * `K × (actual - expected)`, where `actual` is 1 for a win and 0 for a loss.
 */
public object Elo {

    /** Conventional K-factor — the maximum single-match swing for an even pairing. */
    public const val DEFAULT_K_FACTOR: Int = 32

    /** Win-probability of [self] against [opponent], in `(0.0, 1.0)`. */
    public fun expectedScore(self: Rating, opponent: Rating): Double =
        1.0 / (1.0 + 10.0.pow((opponent.value - self.value) / 400.0))

    /**
     * New `(winner, loser)` ratings after [winner] beats [loser]. Both are
     * clamped via [Rating.of]; the winner never loses points and the loser
     * never gains them for a decisive result.
     */
    public fun updated(
        winner: Rating,
        loser: Rating,
        kFactor: Int = DEFAULT_K_FACTOR,
    ): Pair<Rating, Rating> {
        val newWinner = deltaAgainst(self = winner, averageOpponent = loser, won = true, kFactor = kFactor)
        val newLoser = deltaAgainst(self = loser, averageOpponent = winner, won = false, kFactor = kFactor)
        return newWinner to newLoser
    }

    /**
     * [self]'s new rating after a single result against an opponent of rating
     * [averageOpponent] — used by team matches where each player is scored
     * against the *average* rating of the opposing side. Clamped via [Rating.of].
     */
    public fun deltaAgainst(
        self: Rating,
        averageOpponent: Rating,
        won: Boolean,
        kFactor: Int = DEFAULT_K_FACTOR,
    ): Rating {
        val expected = expectedScore(self, averageOpponent)
        val actual = if (won) 1.0 else 0.0
        return Rating.of(self.value + (kFactor * (actual - expected)).roundToInt())
    }
}
