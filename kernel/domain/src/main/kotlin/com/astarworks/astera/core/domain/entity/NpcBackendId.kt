package com.astarworks.astera.core.domain.entity

/**
 * Identity of an NPC backend (e.g. `"minimal"`, `"citizens"`, future
 * `"mythicmobs"`).
 *
 * A value class rather than a plain `String` so the type system catches
 * mix-ups between a backend id and a [SpawnSpec.Mob.typeKey] (which are also
 * raw strings). The "well-known" ids are listed in [Wellknown] for use in
 * Konsist tests and adapter implementations.
 *
 * Per ADR-0082 (Entity spawning + NPC two-tier): every [com.astarworks.astera.core.application.port.outbound.entity.INpcAdapter]
 * implementation declares its [NpcBackendId] via `backendId()`, and the
 * Konsist `INpcAdapter implementations must declare backendId() uniquely`
 * test verifies no two adapters collide.
 */
@JvmInline
public value class NpcBackendId(public val value: String) {
    init {
        require(value.isNotBlank()) { "NpcBackendId must not be blank" }
        require(value.all { it.isLowerCase() || it.isDigit() || it == '_' || it == '-' }) {
            "NpcBackendId must be lowercase [a-z0-9_-]; got '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        /** Built-in minimal adapter (ArmorStand / Display). Always available. */
        public val MINIMAL: NpcBackendId = NpcBackendId("minimal")

        /** Citizens-plugin-backed adapter. Available only when Citizens is loaded. */
        public val CITIZENS: NpcBackendId = NpcBackendId("citizens")
    }
}
