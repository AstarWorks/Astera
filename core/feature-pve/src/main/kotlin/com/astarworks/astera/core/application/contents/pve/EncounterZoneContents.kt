package com.astarworks.astera.core.application.contents.pve

import com.astarworks.astera.core.application.config.mob.pve.EncounterZoneYamlConfig
import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.astarworks.astera.core.application.port.outbound.mob.IEncounterZoneRegistry
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.mob.encounter.EncounterZone
import com.astarworks.astera.core.domain.mob.encounter.EncounterZoneId

/**
 * Encounter-zone-side `Contents<EncounterZoneId, EncounterZone>` substrate
 * (Wave 5pve-content, ADR-0099).
 *
 * Same shape as [com.astarworks.astera.core.application.contents.MobContents]
 * (ADR-0085 substrate):
 *  - `find / all` from `Contents` (and exposed via [IEncounterZoneRegistry])
 *  - `bootstrap` for `onEnable` initial load (best-effort)
 *  - `reload` for `/astera reload encounters` (atomic swap on success)
 *  - `register / clear` for tests + admin commands
 *
 * Wires the YAML registry into the same port the in-memory Wave 5pve-boss
 * impl exposes ([IEncounterZoneRegistry]), so the lifecycle service +
 * lookup callers see one homogeneous registry surface regardless of whether
 * entries came from YAML or programmatic register.
 *
 * Registration with [com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry]
 * happens at `onEnable` in `AsteraCorePlugin.kt` (Phase C orchestrator work).
 */
public class EncounterZoneContents(
    parsers: List<IContentParser> = ContentLoader.DEFAULT_PARSERS,
) : YamlDynamicContents<EncounterZoneId, EncounterZone, EncounterZoneYamlConfig>(
    categoryName = "pve/encounters",
    deserializer = EncounterZoneYamlConfig.serializer(),
    parsers = parsers,
),
    IEncounterZoneRegistry {

    override fun toEntry(config: EncounterZoneYamlConfig): Pair<EncounterZoneId, EncounterZone> =
        config.toEntry()

    /**
     * [IEncounterZoneRegistry] port surface for `register(zone)` — delegates to
     * the [YamlDynamicContents.register] mutator using the zone's own id.
     */
    override fun register(zone: EncounterZone) {
        register(zone.id, zone)
    }

    override fun get(id: EncounterZoneId): EncounterZone? = find(id)

    // [Contents.all] returns `Collection<V>` (and is `final` on
    // [YamlDynamicContents] — no override needed); [IEncounterZoneRegistry.all]
    // now also returns `Collection<EncounterZone>` so the same method
    // satisfies both port surfaces without redeclaration.

    override fun size(): Int = super<YamlDynamicContents>.all().size
}
