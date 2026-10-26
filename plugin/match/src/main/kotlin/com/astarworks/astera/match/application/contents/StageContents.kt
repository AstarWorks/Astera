package com.astarworks.astera.match.application.contents

import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.config.StageYamlConfig

/**
 * Stage-side `Contents<StageId, Stage>` (Wave _infra-2b, ADR-0065).
 *
 * Replaces the legacy `InMemoryStageRepository` + `StageLoaderService` pair.
 * Inherits from [YamlDynamicContents]:
 *
 *   - `find(id) / all()` from [com.astarworks.astera.core.application.contents.Contents]
 *     (the `IStageRegistry` port surface that every stage consumer uses)
 *   - `bootstrap(dir)` for `onEnable` initial load (best-effort)
 *   - `reload(dir)` for `/astera reload stages` (all-or-nothing, atomic swap)
 *   - `register(id, spec) / clear()` for tests + programmatic registration
 *
 * No state of its own — the substrate's `AtomicReference<Map<StageId, Stage>>`
 * is the single source of truth and is read by every `IStageRegistry`
 * consumer through `find()` (so concurrent readers always see a
 * self-consistent snapshot, never a torn map).
 */
public class StageContents(private val instances: (StageId) -> Stage? = { null }) : YamlDynamicContents<StageId, Stage, StageYamlConfig>(
    categoryName = "stages",
    deserializer = StageYamlConfig.serializer(),
), IStageRegistry {

    override fun find(key: StageId): Stage? = instances(key) ?: super.find(key)

    /**
     * Convert each YAML DTO to its `(StageId, Stage)` entry. Delegates to
     * [StageYamlConfig.toSpec] so any future schema evolution stays
     * single-sourced in the DTO.
     */
    override fun toEntry(config: StageYamlConfig): Pair<StageId, Stage> {
        val stage = config.toSpec()
        return stage.id to stage
    }

    /**
     * Convenience overload: register by spec alone (the spec owns its id).
     */
    public fun register(stage: Stage) {
        register(stage.id, stage)
    }
}
