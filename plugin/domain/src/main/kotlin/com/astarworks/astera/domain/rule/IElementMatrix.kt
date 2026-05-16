package com.astarworks.astera.domain.rule

import com.astarworks.astera.domain.model.weapon.DamageAttribute

/**
 * Attacker-vs-defender element matchup table.
 *
 * Multiplier semantics: `1.0` = neutral, `> 1.0` = attacker is strong against
 * the defender (deal more damage), `< 1.0` = attacker is weak. The matrix is
 * a pure lookup; it owns no I/O and no state mutation.
 *
 * Kept in `domain/rule/` (not `application/port/`) because [DamageRule.resolve]
 * needs to invoke it directly — moving it to `application` would force callers
 * to pre-resolve the multiplier and pass a `Double`, leaking the matchup logic
 * out of the rule. Implementations (YAML-backed, in-memory, etc.) live in
 * `application/service/`; this is the same pattern used by other pure-function
 * domain interfaces in Astera.
 */
public fun interface IElementMatrix {

    /**
     * Damage multiplier when [attacker]-attributed damage lands on a defender
     * with attribute [defender]. Returns `1.0` for any unknown pair so callers
     * never need to null-check.
     */
    public fun multiplier(attacker: DamageAttribute, defender: DamageAttribute): Double

    public companion object {
        /** Trivial matrix that always returns `1.0`. Useful for tests and as a
         *  safe default in [DamageRule.resolve]. */
        public val Neutral: IElementMatrix = IElementMatrix { _, _ -> 1.0 }
    }
}
