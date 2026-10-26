package com.astarworks.astera.core.domain.mob

/**
 * Region reference used by [SpawnConditions.regionId] (Wave 5pve, ADR-0085).
 *
 * # Why a mob-local type
 *
 * Wave 5d / 5n introduce a server-wide `RegionId` for world env + region
 * tagging, but those waves are in-flight on parallel branches and have not
 * been merged into mainline at the time of Wave 5pve. To avoid forward
 * coupling we define a mob-scoped value class here; once Wave 5n's
 * `core.domain.world.RegionId` lands on mainline, [MobRegionId] becomes a
 * `typealias` (or is removed — supersede ADR), per `.claude/rules/coding-posture.md
 * §Pre-release breaking changes`.
 *
 * Naming rule: lower-kebab-case (`forest-edge`, `desert-arena-3`).
 */
@JvmInline
public value class MobRegionId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "MobRegionId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
