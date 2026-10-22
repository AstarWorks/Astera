package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.config.EgoYamlConfig
import com.astarworks.astera.core.domain.ego.EgoConfig
import com.charleskorn.kaml.Yaml
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Loads `content/balance/ego.yaml` (ADR-0231); fail-soft to [EgoConfig.DEFAULTS] like [BlockDurabilityLoader]. */
public class EgoConfigLoader {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun load(path: Path): EgoConfig {
        if (!path.exists()) {
            log.info("ego.yaml not found at {}; using defaults", path)
            return EgoConfig.DEFAULTS
        }
        return runCatching { Yaml.default.decodeFromString(EgoYamlConfig.serializer(), path.readText()).toDomain() }
            .getOrElse {
                log.warn("Invalid ego.yaml at {}: {}; using defaults", path, it.message)
                EgoConfig.DEFAULTS
            }
    }
}
