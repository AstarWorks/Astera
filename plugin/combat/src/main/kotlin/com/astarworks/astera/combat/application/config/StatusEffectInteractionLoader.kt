package com.astarworks.astera.combat.application.config

import com.charleskorn.kaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loader for `content/balance/status-effect-interactions.yaml` (Wave 5o,
 * ADR-0081).
 *
 * Falls back to an empty config (no rules → resolver always returns
 * [com.astarworks.astera.combat.domain.status.interaction.StatusInteraction.Pass])
 * when:
 *  - the file is missing entirely (an Annihilation deploy that did not opt in
 *    to interactions yet), or
 *  - the file is present but YAML-empty (operator intentionally cleared it).
 *
 * A malformed file (parse error, schema error) is propagated as-is so the
 * plugin enable surfaces the problem instead of silently degrading.
 */
public object StatusEffectInteractionLoader {

    public fun load(path: Path): StatusEffectInteractionConfig {
        if (!Files.isRegularFile(path)) return StatusEffectInteractionConfig()
        val raw = Files.readString(path).trim()
        if (raw.isEmpty()) return StatusEffectInteractionConfig()
        return Yaml.default.decodeFromString(StatusEffectInteractionConfig.serializer(), raw)
    }
}
