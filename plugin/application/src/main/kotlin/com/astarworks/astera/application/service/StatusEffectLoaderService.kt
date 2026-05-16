package com.astarworks.astera.application.service

import com.astarworks.astera.application.config.StatusEffectYamlConfig
import com.astarworks.astera.application.config.parser.JsonContentParser
import com.astarworks.astera.application.config.parser.TomlContentParser
import com.astarworks.astera.application.config.parser.YamlContentParser
import com.astarworks.astera.application.port.outbound.IContentParser
import java.nio.file.Path

/**
 * Reads status-effect definition files under `content/status/` and registers
 * parsed effect specs in the supplied [MutableStatusRegistry] (Stage 1 J).
 *
 * Thin wrapper over [ContentLoader] specialized to [StatusEffectYamlConfig]:
 * supplies the default multi-format parser list (YAML / TOML / JSON per
 * ADR-0016) and converts each loaded DTO to a domain
 * [com.astarworks.astera.domain.model.status.StatusEffectSpec] via
 * [StatusEffectYamlConfig.toSpec] before handing it to the registry.
 *
 * Failure mode: a single bad file logs an error and is skipped; the rest still
 * load. Phase 1 has no hot-reload — call [loadFrom] once at startup.
 */
public class StatusEffectLoaderService(
    private val registry: MutableStatusRegistry,
    parsers: List<IContentParser> = DEFAULT_PARSERS,
) {
    private val loader = ContentLoader(
        parsers = parsers,
        deserializer = StatusEffectYamlConfig.serializer(),
    )

    /** Loads every supported content file in [dir]. Missing dir = warn + no-op. */
    public fun loadFrom(dir: Path): LoadReport {
        val report = loader.loadFrom(dir) { cfg ->
            registry.register(cfg.toSpec())
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
