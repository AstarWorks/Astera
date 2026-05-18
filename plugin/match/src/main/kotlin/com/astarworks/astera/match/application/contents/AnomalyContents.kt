package com.astarworks.astera.match.application.contents

import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.config.AnomalyScheduleYamlConfig
import com.astarworks.astera.match.application.port.outbound.IAnomalyRegistry
import com.astarworks.astera.match.domain.anomaly.AnomalyDescriptor

/**
 * Anomaly-side `Contents<StageId, List<AnomalyDescriptor>>` (Wave _infra-2b, ADR-0065).
 *
 * Reads `content/anomaly/<stage-id>.yaml` (top-level
 * [AnomalyScheduleYamlConfig]: `stage_id` + a list of anomaly descriptors).
 * Inherits from [YamlDynamicContents]:
 *
 *   - `find(stageId) / all()` from
 *     [com.astarworks.astera.core.application.contents.Contents]
 *     (the `IAnomalyRegistry` port surface)
 *   - `bootstrap(dir)` for `onEnable` initial load (best-effort)
 *   - `reload(dir)` for `/astera reload anomaly` (all-or-nothing)
 *   - `register(stageId, descriptors) / clear()` for tests + programmatic
 *
 * The DTO's `toDescriptor()` returns `Result<AnomalyDescriptor, ConfigError>`
 * (kebab / enum / range validation). Any failure inside the list is
 * normalized to an `IllegalArgumentException` at `toEntry` time so the
 * `ContentLoader` substrate logs + counts the file as failed (matching the
 * "no raw JVM throwables in application API" rule — the throw stays inside
 * the loader's catch boundary).
 */
public class AnomalyContents : YamlDynamicContents<StageId, List<AnomalyDescriptor>, AnomalyScheduleYamlConfig>(
    categoryName = "anomaly",
    deserializer = AnomalyScheduleYamlConfig.serializer(),
), IAnomalyRegistry {

    override fun toEntry(config: AnomalyScheduleYamlConfig): Pair<StageId, List<AnomalyDescriptor>> {
        val stageId = StageId(config.stageId)
        val descriptors = config.anomalies.map { anomalyDto ->
            when (val r = anomalyDto.toDescriptor()) {
                is Result.Success -> r.value
                is Result.Failure -> throw IllegalArgumentException(
                    "Invalid anomaly for stage '${config.stageId}': ${r.error}",
                )
            }
        }
        return stageId to descriptors
    }

    /**
     * Convenience overload: register a schedule by stage id alone (the
     * envelope owns its `stageId`).
     */
    public fun register(schedule: AnomalyScheduleYamlConfig) {
        val (stageId, descriptors) = toEntry(schedule)
        register(stageId, descriptors)
    }
}
