package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.mob.MobYamlConfig
import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.astarworks.astera.core.application.port.outbound.mob.IMobRegistry
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.mob.MobSpec
import com.astarworks.astera.core.domain.mob.MobTypeId

/**
 * Mob-side `Contents<MobTypeId, MobSpec>` (Wave 5pve, ADR-0085).
 *
 * Same `YamlDynamicContents` base as [ItemContents] / [ParticleContents]:
 *
 *   - `find(id) / all()` from [Contents] (the [IMobRegistry] port surface)
 *   - `bootstrap(dir)` for `onEnable` initial load (best-effort)
 *   - `reload(dir)` for `/astera reload mobs` (all-or-nothing, atomic swap)
 *   - `register(id, spec) / clear()` for tests + admin commands
 *
 * Registration with [com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry]
 * happens at `onEnable` time in `AsteraCorePlugin.kt` (Phase C orchestrator
 * work).
 */
public class MobContents(
    parsers: List<IContentParser> = ContentLoader.DEFAULT_PARSERS,
) : YamlDynamicContents<MobTypeId, MobSpec, MobYamlConfig>(
    categoryName = "mobs",
    deserializer = MobYamlConfig.serializer(),
    parsers = parsers,
),
    IMobRegistry {

    /**
     * Convert each YAML DTO to its `(MobTypeId, MobSpec)` entry. Delegates
     * to [MobYamlConfig.toSpec]; any `IllegalArgumentException` is caught
     * by [ContentLoader] and counted as a failed load.
     */
    override fun toEntry(config: MobYamlConfig): Pair<MobTypeId, MobSpec> {
        val spec = config.toSpec()
        return spec.id to spec
    }

    /** Convenience overload — register by spec alone (the spec owns its id). */
    public fun register(spec: MobSpec) {
        register(spec.id, spec)
    }
}
