package com.astarworks.astera.core.domain.skill.phase

import com.astarworks.astera.core.domain.skill.mechanic.Mechanic

/**
 * Per-archetype phase → mechanic-list mapping carried on a `SkillSpec`.
 *
 * Each archetype has its own [PhaseMechanics] subclass with one field per
 * phase enum value, so YAML can omit phases without sentinel
 * Map-of-empty-list noise and `when` dispatch in the handler is exhaustive.
 *
 * Wave _skills-2 / ADR-0077.
 */
public sealed class PhaseMechanics {

    /** Mechanics for [ProjectilePhase] lifecycle. */
    public data class Projectile(
        public val cast: List<Mechanic> = emptyList(),
        public val hitEntity: List<Mechanic> = emptyList(),
        public val hitBlock: List<Mechanic> = emptyList(),
        public val miss: List<Mechanic> = emptyList(),
        public val expire: List<Mechanic> = emptyList(),
    ) : PhaseMechanics()

    /** Mechanics for [MeleePhase] lifecycle. */
    public data class Melee(
        public val cast: List<Mechanic> = emptyList(),
        public val hitEntity: List<Mechanic> = emptyList(),
        public val miss: List<Mechanic> = emptyList(),
    ) : PhaseMechanics()

    /** Mechanics for [AoePhase] lifecycle. */
    public data class Aoe(
        public val cast: List<Mechanic> = emptyList(),
        public val hitEntity: List<Mechanic> = emptyList(),
        public val expire: List<Mechanic> = emptyList(),
    ) : PhaseMechanics()

    /** Mechanics for [BuffPhase] lifecycle. */
    public data class Buff(
        public val cast: List<Mechanic> = emptyList(),
        public val expire: List<Mechanic> = emptyList(),
        public val removed: List<Mechanic> = emptyList(),
    ) : PhaseMechanics()

    /** Mechanics for [TeleportPhase] lifecycle. */
    public data class Teleport(
        public val cast: List<Mechanic> = emptyList(),
        public val preTeleport: List<Mechanic> = emptyList(),
        public val postTeleport: List<Mechanic> = emptyList(),
    ) : PhaseMechanics()

    /** Mechanics for [SummonPhase] lifecycle. */
    public data class Summon(
        public val cast: List<Mechanic> = emptyList(),
        public val spawn: List<Mechanic> = emptyList(),
        public val summonDeath: List<Mechanic> = emptyList(),
    ) : PhaseMechanics()
}
