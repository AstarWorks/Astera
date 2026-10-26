package com.astarworks.astera.core.domain.mob.boss

/**
 * Stable identifier for a [BossSpec] registered with `IBossRegistry` (or, in
 * Phase 1 PoC, hard-coded by the boss lifecycle service) (Wave 5pve-boss,
 * ADR-0098).
 *
 * Distinct namespace from [com.astarworks.astera.core.domain.mob.MobTypeId]:
 * bosses are first-class encounter content (raid-locked, phase-driven), not
 * just "a mob with more HP". Keeping the id types separate prevents
 * accidentally registering a boss as a regular mob in `IMobRegistry` (which
 * would let it spawn through the open-world spawn ladder).
 *
 * Naming rule: namespaced lower-kebab-case (`astera:scorched-titan`,
 * `astera:abyssal-lich`).
 */
@JvmInline
public value class BossId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "BossId must be namespaced lower-kebab-case (e.g., astera:scorched-titan): '$value'"
        }
    }

    /** Namespace segment (before the colon). */
    public val namespace: String get() = value.substringBefore(':')

    /** Path segment (after the colon). */
    public val path: String get() = value.substringAfter(':')

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9_]*:[a-z][a-z0-9-]*$")
    }
}
