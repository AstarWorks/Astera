package com.astarworks.astera.core.domain.effect

/**
 * Forward-reference identifier for a particle animation sequence. The Phase
 * 4 particle scheduler wave will give this semantics (timed multi-shape
 * sequences with per-tick interpolation); for now it is opaque so
 * `Mechanic.ScheduleParticleAnimation` can reference an asset that will be
 * authored under `content/particles/animations/<id>.yaml` later.
 *
 * Wave _skills-2 / ADR-0077.
 */
@JvmInline
public value class ParticleAnimationId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "ParticleAnimationId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
