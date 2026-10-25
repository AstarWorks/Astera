package com.astarworks.astera.core.domain.item

import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics

/**
 * Per-binding decoration that lets an item shape a skill's [PhaseMechanics]
 * without mutating the skill spec itself. Each phase has an
 * `Append` / `Prepend` / `Replace` triplet:
 *
 *  - `*Replace`: when non-null, throws away the skill's base list and uses
 *    this list instead.
 *  - `*Prepend` + base + `*Append`: when `replace` is null, the binding's
 *    prepend list runs first, then the skill's base list, then the binding's
 *    append list.
 *
 * Archetype-aware via [applyTo]: only the phases relevant to the supplied
 * [PhaseMechanics] variant are consulted. Irrelevant fields are silently
 * ignored, matching the loader's "single overrides DTO across all
 * archetypes" wire shape (see [com.astarworks.astera.core.application.config.PhaseOverridesYaml]).
 *
 * Wave _skills-2 / ADR-0077 (renamed from `HookOverrides` — but Wave
 * _items-1 / _skills-1 never actually landed `HookOverrides` to HEAD, so
 * this is a fresh field on [ItemSkillBinding]).
 */
public data class PhaseOverrides(
    public val castAppend: List<Mechanic> = emptyList(),
    public val castPrepend: List<Mechanic> = emptyList(),
    public val castReplace: List<Mechanic>? = null,
    public val hitEntityAppend: List<Mechanic> = emptyList(),
    public val hitEntityPrepend: List<Mechanic> = emptyList(),
    public val hitEntityReplace: List<Mechanic>? = null,
    public val hitBlockAppend: List<Mechanic> = emptyList(),
    public val hitBlockPrepend: List<Mechanic> = emptyList(),
    public val hitBlockReplace: List<Mechanic>? = null,
    public val missAppend: List<Mechanic> = emptyList(),
    public val missPrepend: List<Mechanic> = emptyList(),
    public val missReplace: List<Mechanic>? = null,
    public val expireAppend: List<Mechanic> = emptyList(),
    public val expirePrepend: List<Mechanic> = emptyList(),
    public val expireReplace: List<Mechanic>? = null,
    public val removedAppend: List<Mechanic> = emptyList(),
    public val removedPrepend: List<Mechanic> = emptyList(),
    public val removedReplace: List<Mechanic>? = null,
    public val preTeleportAppend: List<Mechanic> = emptyList(),
    public val preTeleportPrepend: List<Mechanic> = emptyList(),
    public val preTeleportReplace: List<Mechanic>? = null,
    public val postTeleportAppend: List<Mechanic> = emptyList(),
    public val postTeleportPrepend: List<Mechanic> = emptyList(),
    public val postTeleportReplace: List<Mechanic>? = null,
    public val spawnAppend: List<Mechanic> = emptyList(),
    public val spawnPrepend: List<Mechanic> = emptyList(),
    public val spawnReplace: List<Mechanic>? = null,
    public val summonDeathAppend: List<Mechanic> = emptyList(),
    public val summonDeathPrepend: List<Mechanic> = emptyList(),
    public val summonDeathReplace: List<Mechanic>? = null,
) {
    /**
     * Apply this overrides instance to a base [PhaseMechanics], producing a
     * decorated PhaseMechanics of the same archetype. Phases the archetype
     * does not own are ignored.
     */
    public fun applyTo(base: PhaseMechanics): PhaseMechanics = when (base) {
        is PhaseMechanics.Projectile -> base.copy(
            cast = compose(base.cast, castPrepend, castAppend, castReplace),
            hitEntity = compose(base.hitEntity, hitEntityPrepend, hitEntityAppend, hitEntityReplace),
            hitBlock = compose(base.hitBlock, hitBlockPrepend, hitBlockAppend, hitBlockReplace),
            miss = compose(base.miss, missPrepend, missAppend, missReplace),
            expire = compose(base.expire, expirePrepend, expireAppend, expireReplace),
        )
        is PhaseMechanics.Melee -> base.copy(
            cast = compose(base.cast, castPrepend, castAppend, castReplace),
            hitEntity = compose(base.hitEntity, hitEntityPrepend, hitEntityAppend, hitEntityReplace),
            miss = compose(base.miss, missPrepend, missAppend, missReplace),
        )
        is PhaseMechanics.Aoe -> base.copy(
            cast = compose(base.cast, castPrepend, castAppend, castReplace),
            hitEntity = compose(base.hitEntity, hitEntityPrepend, hitEntityAppend, hitEntityReplace),
            expire = compose(base.expire, expirePrepend, expireAppend, expireReplace),
        )
        is PhaseMechanics.Buff -> base.copy(
            cast = compose(base.cast, castPrepend, castAppend, castReplace),
            expire = compose(base.expire, expirePrepend, expireAppend, expireReplace),
            removed = compose(base.removed, removedPrepend, removedAppend, removedReplace),
        )
        is PhaseMechanics.Teleport -> base.copy(
            cast = compose(base.cast, castPrepend, castAppend, castReplace),
            preTeleport = compose(base.preTeleport, preTeleportPrepend, preTeleportAppend, preTeleportReplace),
            postTeleport = compose(base.postTeleport, postTeleportPrepend, postTeleportAppend, postTeleportReplace),
        )
        is PhaseMechanics.Sequence -> base.copy(
            cast = compose(base.cast, castPrepend, castAppend, castReplace),
        )
        is PhaseMechanics.Summon -> base.copy(
            cast = compose(base.cast, castPrepend, castAppend, castReplace),
            spawn = compose(base.spawn, spawnPrepend, spawnAppend, spawnReplace),
            summonDeath = compose(base.summonDeath, summonDeathPrepend, summonDeathAppend, summonDeathReplace),
        )
    }

    private fun compose(
        base: List<Mechanic>,
        prepend: List<Mechanic>,
        append: List<Mechanic>,
        replace: List<Mechanic>?,
    ): List<Mechanic> =
        if (replace != null) replace else prepend + base + append

    public companion object {
        public val EMPTY: PhaseOverrides = PhaseOverrides()
    }
}
