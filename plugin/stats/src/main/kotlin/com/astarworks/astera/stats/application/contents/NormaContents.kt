package com.astarworks.astera.stats.application.contents

import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.core.domain.norma.Norma
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.stats.application.config.NormaYamlConfig
import com.astarworks.astera.stats.application.port.outbound.INormaRegistry

/**
 * Norma-side `Contents<NormaId, Norma>` (Wave _infra-2b, ADR-0065).
 *
 * Replaces the legacy `NormaLoaderService` + the definition-storage half of
 * `InMemoryNormaService`. Inherits from [YamlDynamicContents]:
 *
 *   - `find(id) / all()` from [com.astarworks.astera.core.application.contents.Contents]
 *     (the `INormaRegistry` port surface)
 *   - `bootstrap(dir)` for `onEnable` initial load (best-effort)
 *   - `reload(dir)` for `/astera reload normas` (all-or-nothing, atomic swap)
 *   - `register(id, spec) / clear()` for tests + programmatic registration
 *
 * No state of its own — the substrate's `AtomicReference<Map<NormaId, Norma>>`
 * is the single source of truth and is read by every `INormaRegistry`
 * consumer through `find()` / `all()` (so concurrent readers always see a
 * self-consistent snapshot, never a torn map).
 *
 * `InMemoryNormaService` is the parallel half: it reads definitions from this
 * registry but owns mutable per-player [com.astarworks.astera.core.domain.norma.MissionProgress]
 * via [com.astarworks.astera.core.application.port.outbound.INormaProgressRepository].
 */
public class NormaContents : YamlDynamicContents<NormaId, Norma, NormaYamlConfig>(
    categoryName = "normas",
    deserializer = NormaYamlConfig.serializer(),
), INormaRegistry {

    /**
     * Convert each YAML DTO to its `(NormaId, Norma)` entry. Delegates to
     * [NormaYamlConfig.toSpec] (throws on unknown period names, caught by
     * the underlying `ContentLoader` and counted as a failed load).
     */
    override fun toEntry(config: NormaYamlConfig): Pair<NormaId, Norma> {
        val norma = config.toSpec()
        return norma.id to norma
    }

    /**
     * Convenience overload: register by spec alone (the spec owns its id).
     * Mirrors the shape used by tests + programmatic admin commands.
     */
    public fun register(norma: Norma) {
        register(norma.id, norma)
    }
}
