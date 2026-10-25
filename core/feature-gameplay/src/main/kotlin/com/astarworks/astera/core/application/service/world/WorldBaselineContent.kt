package com.astarworks.astera.core.application.service.world

import com.astarworks.astera.core.application.config.WorldBaselineYamlConfig
import com.astarworks.astera.core.application.service.ContentLoader
import java.nio.file.Path

/** Loads `content/world-rules/baseline.yaml` (ADR-0245); a missing or broken file falls back to the safe defaults. */
public object WorldBaselineContent {

    public fun load(contentDir: Path): WorldBaselineYamlConfig {
        val loader = ContentLoader(ContentLoader.DEFAULT_PARSERS, WorldBaselineYamlConfig.serializer())
        val found = mutableListOf<WorldBaselineYamlConfig>()
        loader.loadFrom(contentDir.resolve("world-rules")) { found += it }
        return found.firstOrNull() ?: WorldBaselineYamlConfig()
    }
}
