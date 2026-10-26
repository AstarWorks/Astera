package com.astarworks.astera.core.application.contents.pve

import com.astarworks.astera.core.application.config.mob.pve.BossYamlConfig
import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.astarworks.astera.core.application.port.outbound.mob.IBossSpecRegistry
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.mob.boss.BossId
import com.astarworks.astera.core.domain.mob.boss.BossSpec

/**
 * Boss-spec-side `Contents<BossId, BossSpec>` substrate
 * (Wave 5pve-content, ADR-0099).
 *
 * Loads `content/pve/bosses/<id>.yaml` and exposes the registry surface
 * through [IBossSpecRegistry] so the lifecycle service can resolve
 * `BossId → BossSpec` without holding mutable spec state itself.
 *
 * Same shape conventions as [EncounterZoneContents] and
 * [com.astarworks.astera.core.application.contents.MobContents]:
 *  - `find / all` from `Contents` (and exposed via [IBossSpecRegistry])
 *  - `bootstrap` for `onEnable` initial load (best-effort)
 *  - `reload` for `/astera reload bosses` (atomic swap on success)
 *  - `register / clear` for tests + admin commands
 */
public class BossSpecContents(
    parsers: List<IContentParser> = ContentLoader.DEFAULT_PARSERS,
) : YamlDynamicContents<BossId, BossSpec, BossYamlConfig>(
    categoryName = "pve/bosses",
    deserializer = BossYamlConfig.serializer(),
    parsers = parsers,
),
    IBossSpecRegistry {

    override fun toEntry(config: BossYamlConfig): Pair<BossId, BossSpec> = config.toEntry()

    /** Port-side `register(spec)` — delegates to the spec's own id. */
    override fun register(spec: BossSpec) {
        register(spec.id, spec)
    }

    override fun get(id: BossId): BossSpec? = find(id)

    // [Contents.all] returns `Collection<V>` (and is `final` on
    // [YamlDynamicContents] — no override needed); [IBossSpecRegistry.all]
    // now also returns `Collection<BossSpec>` so the same method satisfies
    // both port surfaces without redeclaration.

    override fun size(): Int = super<YamlDynamicContents>.all().size
}
