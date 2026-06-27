package com.astarworks.astera.match.domain.rating

/**
 * A player's competitive Elo rating (Wave 7b, ADR-0037).
 *
 * Bounded to `[MIN, MAX]` so a runaway loss/win streak cannot drive the value
 * past sane display / matchmaking bounds. The Elo update math itself lives in
 * `match.domain.rating.Elo` (W1/W2) — this is a pure value object.
 */
@JvmInline
public value class Rating(public val value: Int) {
    init {
        require(value in MIN..MAX) { "Rating must be in [$MIN, $MAX]: $value" }
    }

    override fun toString(): String = value.toString()

    public companion object {
        public const val MIN: Int = 0
        public const val MAX: Int = 5000

        /** Starting rating for a player with no recorded games. */
        public val DEFAULT: Rating = Rating(1000)

        /** Clamp an arbitrary int into the valid rating range. */
        public fun of(value: Int): Rating = Rating(value.coerceIn(MIN, MAX))
    }
}
