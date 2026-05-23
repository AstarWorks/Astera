package com.astarworks.astera.core.application.port.outbound.mob

import com.astarworks.astera.core.domain.mob.boss.BossId
import com.astarworks.astera.core.domain.mob.boss.BossSpec

/**
 * Outbound port: registry of [BossSpec] definitions loaded from
 * `content/pve/bosses/<id>.yaml` (Wave 5pve-content, ADR-0099).
 *
 * Wave 5pve-boss (ADR-0098) shipped [IBossLifecycleService] with
 * `registerSpec()` / `specOf()` mutators on the lifecycle service itself —
 * adequate for the foundation, but conflates two responsibilities (spec
 * lookup vs orchestration state). Wave 5pve-content (this ADR) splits the
 * lookup behind this dedicated registry so:
 *
 *  - YAML loading lives in a `BossSpecContents : YamlDynamicContents` (Phase
 *    1 ships this) rather than scattered around plugin wiring
 *  - the lifecycle service can consume the registry as a read-only
 *    dependency without holding mutable spec state itself
 *  - Phase 4 reload (`/astera reload bosses`) can swap specs without
 *    touching live boss instances
 *
 * # Why mirror `IEncounterZoneRegistry` shape
 *
 * Same `register / get / all / size` surface as [IEncounterZoneRegistry] so
 * the YamlDynamicContents subclasses share behaviour (`BossSpecContents` and
 * `EncounterZoneContents`). Per ADR-0017 substrate consistency, content
 * registries follow this shape unless there's an explicit reason to deviate.
 *
 * # Why a port, not just a Map
 *
 * Persistence / overlay opportunities. Same rationale as
 * [IEncounterZoneRegistry]: a future cross-server overlay could read boss
 * specs from a central catalog rather than per-server YAML.
 */
public interface IBossSpecRegistry {

    /** Register a boss spec (Phase 1: in-memory + YAML; Phase 4: with overlay). */
    public fun register(spec: BossSpec)

    /** Look up a boss spec by id, or null if not registered. */
    public fun get(id: BossId): BossSpec?

    /**
     * All registered boss specs. Iteration order is insertion order.
     *
     * Returns [Collection] (not [List]) for the same reason as
     * [IEncounterZoneRegistry.all]: lets [com.astarworks.astera.core.application.contents.YamlDynamicContents]-
     * backed implementations satisfy the port without a defensive copy.
     */
    public fun all(): Collection<BossSpec>

    /** Number of registered boss specs. */
    public fun size(): Int
}
