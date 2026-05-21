package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.config.StarterGrantYamlConfig
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.StarterGrant
import com.charleskorn.kaml.Yaml
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Loads `content/balance/starter-grant.yaml` into a [StarterGrant]
 * (Wave 5e-fix).
 *
 * Mirrors [BlockDurabilityLoader] (Wave 5d) — single file, kaml-decoded,
 * fail-soft to [StarterGrant.EMPTY] on every recoverable failure so an
 * Astera server boots even with missing or malformed balance content.
 *
 * # Failure mode
 *
 * - Missing file → log INFO, return [StarterGrant.EMPTY].
 * - Read failure → log WARN, return EMPTY.
 * - YAML parse failure → log WARN, return EMPTY.
 * - Validation failure ([com.astarworks.astera.core.application.config.ConfigError])
 *   → log WARN with the error detail, return EMPTY.
 * - Success → return the parsed grant.
 *
 * EMPTY on failure is intentional: a first-time player on a broken server
 * gets a Lobby with no starter items but DOES get a profile created. That
 * matches the "the join must complete" invariant
 * [com.astarworks.astera.core.application.eventbus.listeners.RestoreInventoryOnPlayerJoinedListener]
 * already documents for inventory restore.
 */
public class StarterGrantLoader {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun load(path: Path): StarterGrant {
        if (!path.exists()) {
            log.info("starter-grant.yaml not found at {}; using empty grant", path)
            return StarterGrant.EMPTY
        }
        val text = runCatching { path.readText() }.getOrElse {
            log.warn("Failed to read starter-grant.yaml at {}: {}; using empty grant", path, it.message)
            return StarterGrant.EMPTY
        }
        val dto = runCatching {
            Yaml.default.decodeFromString(StarterGrantYamlConfig.serializer(), text)
        }.getOrElse {
            log.warn("YAML parse failure for starter-grant.yaml at {}: {}; using empty grant", path, it.message)
            return StarterGrant.EMPTY
        }
        return when (val result = dto.toDomain()) {
            is Result.Success -> result.value
            is Result.Failure -> {
                log.warn(
                    "Validation failure for starter-grant.yaml at {}: {}; using empty grant",
                    path,
                    result.error.detail,
                )
                StarterGrant.EMPTY
            }
        }
    }
}
