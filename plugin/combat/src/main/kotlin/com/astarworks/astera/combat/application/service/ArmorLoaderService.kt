package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.config.ArmorYamlConfig
import com.astarworks.astera.core.application.config.parser.JsonContentParser
import com.astarworks.astera.core.application.config.parser.TomlContentParser
import com.astarworks.astera.core.application.config.parser.YamlContentParser
import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.astarworks.astera.core.application.service.ContentLoader
import java.nio.file.Path

/**
 * Reads armor definition files under `content/armor/` and populates a
 * [MutableArmorRegistry] with both individual pieces and named sets
 * (Wave 5a, worker α — mirrors [SkillLoaderService]).
 *
 * The `content/armor/` directory holds a polymorphic mix of [ArmorYamlConfig]
 * entries (`type: piece` or `type: set`); each entry is routed to the
 * appropriate registry slot based on its sealed sub-type. A single bad file
 * logs an error and is skipped; the rest still load (per [ContentLoader]'s
 * fail-isolated batch semantics).
 *
 * No hot-reload in Phase 1 — call [loadFrom] once at startup.
 */
public class ArmorLoaderService(
    private val registry: MutableArmorRegistry,
    parsers: List<IContentParser> = DEFAULT_PARSERS,
) {
    private val loader = ContentLoader(
        parsers = parsers,
        deserializer = ArmorYamlConfig.serializer(),
    )

    /** Loads every supported content file in [dir]. Missing dir = warn + no-op. */
    public fun loadFrom(dir: Path): LoadReport {
        val report = loader.loadFrom(dir) { entry ->
            when (entry) {
                is ArmorYamlConfig.ArmorPieceEntry -> registry.register(entry.toSpec())
                is ArmorYamlConfig.ArmorSetEntry -> registry.registerSet(entry.toSet())
            }
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
