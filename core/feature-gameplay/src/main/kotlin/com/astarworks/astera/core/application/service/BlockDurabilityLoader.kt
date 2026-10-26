package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.config.BlockDurabilityYamlConfig
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.world.BlockDurabilityRule
import com.charleskorn.kaml.Yaml
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Loads `content/balance/block-durability.yaml` into a [BlockDurabilityRule]
 * (Wave 5d).
 *
 * # Failure mode
 *
 * Fail-soft: a missing or malformed file falls back to
 * [BlockDurabilityRule.defaultMatrix] so a server boots even with corrupted
 * balance content. The failure is logged at the appropriate level so server
 * operators see it without the JVM crashing.
 *
 * Wave 7c may revisit this to make balance failures fatal once content
 * tooling (schema-gen) catches authoring mistakes pre-deploy.
 *
 * Loaded as a single file (not a directory walk) because there is one
 * balance matrix per server — unlike `content/weapons/`, splitting it across
 * files would just make the matrix harder to read.
 */
public class BlockDurabilityLoader {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Load + validate the durability rule at [path].
     *
     *  - Missing file → log INFO, return [BlockDurabilityRule.defaultMatrix].
     *  - Read failure → log WARN, return default matrix.
     *  - YAML parse failure → log WARN, return default matrix.
     *  - Validation failure ([com.astarworks.astera.core.application.config.ConfigError])
     *    → log WARN with the error detail, return default matrix.
     *  - Success → return the parsed rule.
     */
    public fun load(path: Path): BlockDurabilityRule {
        if (!path.exists()) {
            log.info("block-durability.yaml not found at {}; using default matrix", path)
            return BlockDurabilityRule.defaultMatrix()
        }
        val text = runCatching { path.readText() }.getOrElse {
            log.warn("Failed to read block-durability.yaml at {}: {}; using default matrix", path, it.message)
            return BlockDurabilityRule.defaultMatrix()
        }
        val dto = runCatching {
            Yaml.default.decodeFromString(BlockDurabilityYamlConfig.serializer(), text)
        }.getOrElse {
            log.warn("YAML parse failure for block-durability.yaml at {}: {}; using default matrix", path, it.message)
            return BlockDurabilityRule.defaultMatrix()
        }
        return when (val result = dto.toDomain()) {
            is Result.Success -> result.value
            is Result.Failure -> {
                log.warn(
                    "Validation failure for block-durability.yaml at {}: {}; using default matrix",
                    path,
                    result.error.detail,
                )
                BlockDurabilityRule.defaultMatrix()
            }
        }
    }
}
