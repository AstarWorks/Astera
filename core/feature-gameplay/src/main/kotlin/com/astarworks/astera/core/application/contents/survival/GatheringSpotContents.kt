package com.astarworks.astera.core.application.contents.survival

import com.astarworks.astera.core.application.config.survival.GatheringSpotYamlConfig
import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.astarworks.astera.core.application.port.outbound.survival.IGatheringSpotRegistry
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.survival.GatheringSpot
import com.astarworks.astera.core.domain.survival.GatheringSpotId

/**
 * `Contents<GatheringSpotId, GatheringSpot>` for `content/gathering/<id>.yaml`
 * (Wave 5pve-survival / ADR-0107).
 *
 * Same `YamlDynamicContents` base as [MobContents] / [ItemContents]:
 *   - `find(id)` / `all()` from `Contents` (the [IGatheringSpotRegistry] surface).
 *   - `bootstrap(dir)` for `onEnable` initial load (best-effort).
 *   - `reload(dir)` for `/astera-survival reload gathering` (atomic swap).
 *   - `register(id, spec)` / `clear()` for tests + admin commands.
 *
 * Registration with `IReloadableContentsRegistry` happens at `onEnable`
 * time in `AsteraCorePlugin.kt` (Phase C orchestrator work).
 */
public class GatheringSpotContents(
    parsers: List<IContentParser> = ContentLoader.DEFAULT_PARSERS,
) : YamlDynamicContents<GatheringSpotId, GatheringSpot, GatheringSpotYamlConfig>(
    categoryName = "gathering",
    deserializer = GatheringSpotYamlConfig.serializer(),
    parsers = parsers,
),
    IGatheringSpotRegistry {

    override fun toEntry(config: GatheringSpotYamlConfig): Pair<GatheringSpotId, GatheringSpot> {
        val spec = config.toSpec()
        return spec.id to spec
    }

    /** Convenience overload — register by spec alone (the spec owns its id). */
    public fun register(spec: GatheringSpot) {
        register(spec.id, spec)
    }
}
