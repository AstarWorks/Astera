package com.astarworks.astera.match.application.contents

import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.match.application.config.GeneratorYamlConfig
import com.astarworks.astera.match.application.port.outbound.IGeneratorRegistry
import com.astarworks.astera.match.domain.generator.GeneratorId

/**
 * Generator-side `Contents<GeneratorId, GeneratorYamlConfig>` (Wave _infra-2b,
 * ADR-0065).
 *
 * Reads `content/generators/<generator-id>.yaml` and stores the parsed DTO
 * directly — see [IGeneratorRegistry] kdoc for the rationale (templates,
 * not runtime instances).
 *
 * Validation defers to [GeneratorYamlConfig.toDomain]'s eventual call at
 * match-start time. Bootstrap-time validation is intentionally limited to
 * the kaml decode (schema shape); deeper kebab / enum / tier violations
 * surface as `Result.Failure(ConfigError)` when the use case projects the
 * template under a `MatchId`. This keeps the registry agnostic of the
 * runtime `MatchId` and avoids a parallel "GeneratorSpec" type that would
 * mirror the DTO 1:1.
 *
 * Inherits from [YamlDynamicContents]:
 *
 *   - `find(generatorId) / all()` from
 *     [com.astarworks.astera.core.application.contents.Contents]
 *   - `bootstrap(dir)` for `onEnable` initial load (best-effort)
 *   - `reload(dir)` for `/astera reload generators` (all-or-nothing)
 *   - `register(id, config) / clear()` for tests + programmatic
 */
public class GeneratorContents : YamlDynamicContents<GeneratorId, GeneratorYamlConfig, GeneratorYamlConfig>(
    categoryName = "generators",
    deserializer = GeneratorYamlConfig.serializer(),
), IGeneratorRegistry {

    override fun toEntry(config: GeneratorYamlConfig): Pair<GeneratorId, GeneratorYamlConfig> {
        // `GeneratorId(...)` enforces lower-kebab; an authoring typo throws
        // here, which ContentLoader catches and counts as failed.
        return GeneratorId(config.id) to config
    }

    /**
     * Convenience overload: register a DTO by parsed id alone (the DTO
     * owns its `id`).
     */
    public fun register(config: GeneratorYamlConfig) {
        register(GeneratorId(config.id), config)
    }
}
