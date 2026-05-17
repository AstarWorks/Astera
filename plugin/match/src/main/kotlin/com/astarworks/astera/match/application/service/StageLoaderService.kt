package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.config.parser.JsonContentParser
import com.astarworks.astera.core.application.config.parser.TomlContentParser
import com.astarworks.astera.core.application.config.parser.YamlContentParser
import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.match.application.config.StageYamlConfig
import com.astarworks.astera.match.application.port.outbound.IStageRepository
import java.nio.file.Path

/**
 * Reads stage definition files under `content/stages/` and stores parsed
 * stages in the supplied [IStageRepository] (typically
 * [InMemoryStageRepository] at startup).
 *
 * Thin wrapper over [ContentLoader] specialized to [StageYamlConfig]: it
 * supplies the default multi-format parser list (YAML / TOML / JSON per
 * ADR-0016) and converts each loaded DTO to a domain `Stage` via
 * [StageYamlConfig.toSpec] before handing it to the repository.
 *
 * Mirrors `WeaponLoaderService` — same DI shape, same failure semantics
 * (one bad file logs an error and is skipped, the rest still load). Phase 1
 * has no hot-reload — call [loadFrom] once at startup.
 */
public class StageLoaderService(
    private val repository: IStageRepository,
    parsers: List<IContentParser> = DEFAULT_PARSERS,
) {
    private val loader = ContentLoader(
        parsers = parsers,
        deserializer = StageYamlConfig.serializer(),
    )

    /** Loads every supported content file in [dir]. Missing dir = warn + no-op. */
    public fun loadFrom(dir: Path): LoadReport {
        val report = loader.loadFrom(dir) { cfg ->
            repository.save(cfg.toSpec())
        }
        return LoadReport(loaded = report.loaded, failed = report.failed)
    }

    public data class LoadReport(val loaded: Int, val failed: Int)

    public companion object {
        /**
         * Default parser list: YAML + TOML + JSON. Matches the ADR-0016
         * Phase 2 mid wire-format support matrix.
         */
        public val DEFAULT_PARSERS: List<IContentParser> = listOf(
            YamlContentParser(),
            TomlContentParser(),
            JsonContentParser(),
        )
    }
}
