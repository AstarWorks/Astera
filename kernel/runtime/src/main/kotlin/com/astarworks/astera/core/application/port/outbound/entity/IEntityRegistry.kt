package com.astarworks.astera.core.application.port.outbound.entity

import com.astarworks.astera.core.domain.entity.EntityTypeSpec

/**
 * Outbound port: vendor-neutral catalog of "what kinds of entities can the
 * server spawn" — used by the spawner to validate [com.astarworks.astera.core.domain.entity.SpawnSpec.Mob.typeKey]
 * and by admin commands for tab-completion.
 *
 * The registry is **read-only** at runtime — entries are populated at
 * `onEnable` from a fixed enumeration (Phase 1 `Wave 6a` Paper impl: every
 * `EntityType` Bukkit knows about, minus a deny-list of dangerous types
 * like END_CRYSTAL). Phase 6 may introduce a YAML-driven custom entity
 * catalog; the read-only contract here keeps that change surface narrow.
 */
public interface IEntityRegistry {

    /**
     * Lookup by canonical key (e.g. `"minecraft:zombie"`, `"astera:guard"`).
     * Returns null when the key is unknown.
     *
     * Lookup is case-insensitive on the namespace + path; implementations
     * normalise to lowercase before matching.
     */
    public fun resolve(typeKey: String): EntityTypeSpec?

    /** Every known entry. Sort order is implementation-defined. */
    public fun all(): Collection<EntityTypeSpec>
}
