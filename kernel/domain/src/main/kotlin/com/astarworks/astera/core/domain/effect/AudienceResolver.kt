package com.astarworks.astera.core.domain.effect

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Pure [Audience] semantics shared by every presentation output (displays, screen flash, camera shake).
 *
 * `CASTER` / `TARGET` without a known caster / bound target fall back to `ALL` (no extra filtering), as documented on [Audience].
 */
public object AudienceResolver {

    /** The members of [pool] that perceive an effect for [audience]. */
    public fun select(pool: Collection<PlayerId>, audience: Audience, caster: PlayerId?, target: PlayerId?): Set<PlayerId> = when (audience) {
        Audience.ALL -> LinkedHashSet(pool)
        Audience.CASTER -> if (caster == null) LinkedHashSet(pool) else pool.filterTo(LinkedHashSet()) { it == caster }
        Audience.TARGET -> if (target == null) LinkedHashSet(pool) else pool.filterTo(LinkedHashSet()) { it == target }
        Audience.OTHERS -> pool.filterTo(LinkedHashSet()) { it != caster }
    }

    /** Candidate viewers of a world display: players near it, plus the caster and the bound target wherever they are. */
    public fun displayPool(nearby: Collection<PlayerId>, caster: PlayerId?, target: PlayerId?): Set<PlayerId> =
        LinkedHashSet(nearby).also { pool ->
            caster?.let(pool::add)
            target?.let(pool::add)
        }
}
