package com.astarworks.astera.core.domain.mob

/**
 * Identity of an [com.astarworks.astera.core.application.port.outbound.mob.IMobAdapter]
 * backend (Wave 5pve, ADR-0085).
 *
 * Same pattern as [com.astarworks.astera.core.domain.entity.NpcBackendId] from
 * Wave 5l (ADR-0082) — a value class wrapping a constrained string so the
 * type system distinguishes backend ids from arbitrary text. Konsist
 * enforces uniqueness across `IMobAdapter` implementations
 * (`IMobAdapter implementations must declare backendId() uniquely`).
 *
 * The Phase 1 well-known ids are:
 *
 *  - [MINIMAL] — Core's always-available adapter. Backed by ArmorStand /
 *    Display entity bookkeeping in Phase 1; Wave 6b's Paper subclass spawns
 *    real entities.
 */
@JvmInline
public value class MobBackendId(public val value: String) {
    init {
        require(value.isNotBlank()) { "MobBackendId must not be blank" }
        require(value.all { it.isLowerCase() || it.isDigit() || it == '_' || it == '-' }) {
            "MobBackendId must be lowercase [a-z0-9_-]; got '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        /** Built-in minimal adapter. Always available. */
        public val MINIMAL: MobBackendId = MobBackendId("minimal")
    }
}
