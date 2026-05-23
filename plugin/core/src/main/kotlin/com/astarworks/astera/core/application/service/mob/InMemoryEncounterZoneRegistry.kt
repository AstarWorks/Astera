package com.astarworks.astera.core.application.service.mob

import com.astarworks.astera.core.application.port.outbound.mob.IEncounterZoneRegistry
import com.astarworks.astera.core.domain.mob.encounter.EncounterZone
import com.astarworks.astera.core.domain.mob.encounter.EncounterZoneId

/**
 * In-memory default impl of [IEncounterZoneRegistry] (Wave 5pve-boss, ADR-0098).
 *
 * Phase 1 commit shape: insertion-ordered map. Last-write-wins on duplicate
 * id registration (the registry is meant to be populated once at boot from
 * YAML in Phase 4; in Phase 1 the lifecycle service tests register
 * directly). No concurrency guards — registration is single-threaded at
 * plugin boot.
 */
public class InMemoryEncounterZoneRegistry : IEncounterZoneRegistry {

    private val zones: LinkedHashMap<EncounterZoneId, EncounterZone> = LinkedHashMap()

    override fun register(zone: EncounterZone) {
        zones[zone.id] = zone
    }

    override fun get(id: EncounterZoneId): EncounterZone? = zones[id]

    override fun all(): List<EncounterZone> = zones.values.toList()

    override fun size(): Int = zones.size
}
