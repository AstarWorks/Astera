package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.config.HealthRegenYamlConfig
import com.astarworks.astera.core.domain.player.physiology.HealthRegenConfig
import com.charleskorn.kaml.Yaml
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Loads `content/balance/health-regen.yaml`; an absent file means defaults, a malformed one throws. */
public class HealthRegenConfigLoader {
    public fun load(path: Path): HealthRegenConfig {
        if (!path.exists()) return HealthRegenConfig.DEFAULTS
        return try {
            Yaml.default.decodeFromString(HealthRegenYamlConfig.serializer(), path.readText()).toDomain()
        } catch (e: Exception) {
            throw IllegalStateException("Invalid health-regen.yaml at $path: ${e.message}", e)
        }
    }
}
