package com.astarworks.astera.core.application.port.outbound.mob

import com.astarworks.astera.core.domain.mob.encounter.EncounterZone
import com.astarworks.astera.core.domain.mob.encounter.EncounterZoneId

/**
 * Outbound port: in-memory registry of [EncounterZone] descriptions
 * (Wave 5pve-boss, ADR-0098).
 *
 * Phase 1 ships the registry shape only. Phase 4 will fold this into a
 * `YamlDynamicContents<EncounterZoneId, EncounterZone, EncounterZoneYamlConfig>`
 * substrate analogous to `MobContents` (ADR-0065) so operators can drop
 * `content/encounters/<zone>.yaml` files in and reload via
 * `/astera reload encounters`.
 *
 * # Why a port and not just a Map
 *
 * Keeping the registry behind a port lets:
 *  - Persistence plugins overlay a higher-priority impl that reads from
 *    Postgres if/when encounter state goes cross-server
 *  - Test harnesses register a fake registry without touching YAML
 *  - Wave 6b Paper layer add an audit/observability decorator
 *
 * The minimal port is read-mostly with a `register` mutator for Phase 1
 * test + plugin-wiring bootstrap.
 */
public interface IEncounterZoneRegistry {

    /** Register a zone (Phase 1: in-memory; Phase 4: loaded from YAML). */
    public fun register(zone: EncounterZone)

    /** Look up a zone by id, or null if not registered. */
    public fun get(id: EncounterZoneId): EncounterZone?

    /** All registered zones. Iteration order is insertion order. */
    public fun all(): List<EncounterZone>

    /** Number of registered zones. */
    public fun size(): Int
}
