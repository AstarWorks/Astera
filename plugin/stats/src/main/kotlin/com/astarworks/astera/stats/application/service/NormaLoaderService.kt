package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.application.config.parser.JsonContentParser
import com.astarworks.astera.core.application.config.parser.TomlContentParser
import com.astarworks.astera.core.application.config.parser.YamlContentParser
import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.stats.application.config.NormaYamlConfig
import com.astarworks.astera.core.domain.norma.Norma
import java.nio.file.Path

/**
 * Reads norma (mission) definition files under `content/norma/` and registers
 * parsed [Norma]s via the supplied [register] callback (Wave 2 R1.a).
 *
 * Thin wrapper over [ContentLoader] specialized to [NormaYamlConfig]: it
 * supplies the default multi-format parser list (YAML / TOML / JSON per
 * ADR-0016) and converts each loaded DTO to a domain [Norma] via
 * [NormaYamlConfig.toSpec] before handing it to the callback.
 *
 * The callback indirection (rather than a direct `MutableNormaRegistry` dep)
 * keeps this loader unaware of how the platform plugin chooses to store the
 * definitions — Phase C wiring will pass a method reference into the
 * concrete [com.astarworks.astera.stats.application.port.outbound.INormaService]
 * implementation (e.g. `InMemoryNormaService::register`).
 *
 * Failure mode: a single bad file logs an error and is skipped; the rest still
 * load. Phase 1 has no hot-reload — call [loadFrom] once at startup.
 */
public class NormaLoaderService(
    private val register: (Norma) -> Unit,
    parsers: List<IContentParser> = DEFAULT_PARSERS,
) {
    private val loader = ContentLoader(
        parsers = parsers,
        deserializer = NormaYamlConfig.serializer(),
    )

    /** Loads every supported content file in [dir]. Missing dir = warn + no-op. */
    public fun loadFrom(dir: Path): LoadReport {
        val report = loader.loadFrom(dir) { cfg ->
            register(cfg.toSpec())
        }
        return LoadReport(loaded = report.loaded, failed = report.failed)
    }

    public data class LoadReport(val loaded: Int, val failed: Int)

    public companion object {
        /**
         * Default parser list: YAML + TOML + JSON. Matches the ADR-0016
         * wire-format support matrix used by [SkillLoaderService].
         */
        public val DEFAULT_PARSERS: List<IContentParser> = listOf(
            YamlContentParser(),
            TomlContentParser(),
            JsonContentParser(),
        )
    }
}
