package com.astarworks.astera.core.domain.mob.encounter

/**
 * Stable identifier for an [EncounterZone] (Wave 5pve-boss, ADR-0098).
 *
 * Encounter zones are named PvE areas (HSB Slayer area, OSRS PvM region,
 * Annihilation `blazing-pit`) with their own spawn rules + difficulty
 * curves. Like [com.astarworks.astera.core.domain.mob.MobTypeId] and
 * [com.astarworks.astera.core.domain.mob.boss.BossId], the id is
 * namespaced lower-kebab-case so first-party / third-party / operator
 * content can coexist.
 */
@JvmInline
public value class EncounterZoneId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "EncounterZoneId must be namespaced lower-kebab-case " +
                "(e.g., astera:blazing-pit): '$value'"
        }
    }

    public val namespace: String get() = value.substringBefore(':')
    public val path: String get() = value.substringAfter(':')

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9_]*:[a-z][a-z0-9-]*$")
    }
}
