package com.astarworks.astera.core.domain.mob.runtime

import java.util.UUID

/**
 * Runtime identifier for an individual live mob instance (Wave 5pve-boss,
 * ADR-0098).
 *
 * Distinct from [com.astarworks.astera.core.domain.mob.MobTypeId] which is
 * the **template** identifier (`astera:goblin`). One template can have many
 * concurrent live instances; each gets its own [MobId] when spawned.
 *
 * Minted by the lifecycle service (`IBossLifecycleService` for bosses;
 * normal mobs reuse the adapter's [com.astarworks.astera.core.domain.entity.EntityHandle.id]
 * directly). Persisted to the V30 `boss_kills` audit table for analytics —
 * server-instance scoped otherwise.
 *
 * Naming: opaque UUID-string. Tests construct deterministic ids by passing
 * a fixed [UUID]; production paths use [random].
 */
@JvmInline
public value class MobId(public val value: String) {
    init {
        require(value.isNotBlank()) { "MobId must not be blank" }
    }

    override fun toString(): String = value

    public companion object {
        public fun random(): MobId = MobId(UUID.randomUUID().toString())
        public fun of(uuid: UUID): MobId = MobId(uuid.toString())
    }
}
