package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.match.application.config.GeneratorYamlConfig
import com.astarworks.astera.match.domain.generator.GeneratorId

/**
 * Outbound port: read-only lookup of authored generator definitions
 * (Wave _infra-2b, ADR-0065).
 *
 * Distinct from [IGeneratorRepository], which holds **runtime** per-match
 * `StarGenerator` instances. This registry holds the **immutable authored
 * templates** loaded from `content/generators/`. At match-start Phase C
 * wiring (Wave 6+) will project the templates that match a stage's id into
 * fresh `StarGenerator` runtime rows under the active `MatchId` via
 * [GeneratorYamlConfig.toDomain].
 *
 * Why `Contents<GeneratorId, GeneratorYamlConfig>` rather than
 * `<GeneratorId, StarGenerator>`: `StarGenerator` carries a `MatchId` and
 * a fresh `IAttributeContainer` instance, neither of which is meaningful
 * for an authored definition. Storing the DTO directly keeps the registry
 * honest (immutable, match-independent) and defers the `toDomain` call to
 * the use case that knows the live `MatchId`.
 *
 * Extends [Contents] so the substrate's `find` / `all` contract is shared
 * with every other content type.
 *
 * TODO(infra-2b orchestrator): if Phase C decides a domain "GeneratorSpec"
 * value type (separating template from instance) would be cleaner than
 * exposing the DTO at this port, revisit. For Wave _infra-2b the DTO is
 * sufficient and avoids a parallel value-type that mirrors the YAML 1:1.
 */
public interface IGeneratorRegistry : Contents<GeneratorId, GeneratorYamlConfig>
