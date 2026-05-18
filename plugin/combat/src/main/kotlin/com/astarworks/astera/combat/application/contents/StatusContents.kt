package com.astarworks.astera.combat.application.contents

import com.astarworks.astera.combat.application.config.StatusEffectYamlConfig
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.core.application.contents.YamlDynamicContents

/**
 * Status-effect-side `Contents<StatusEffectId, StatusEffectSpec>` (Wave
 * _infra-2b, ADR-0065).
 *
 * Replaces the legacy `MutableStatusRegistry` + `StatusEffectLoaderService`
 * pair with a single instance that owns an atomic-swap store and participates
 * in the cross-plugin reload registry.
 *
 * Inherits:
 *
 *   - `find(id) / all()` from [com.astarworks.astera.core.application.contents.Contents]
 *     (the `IStatusEffectRegistry` port surface)
 *   - `bootstrap(dir)` for `onEnable` initial load (best-effort)
 *   - `reload(dir)` for `/astera reload status` (all-or-nothing, atomic swap)
 *   - `register(id, spec) / clear()` for tests and programmatic registration
 *
 * Cross-field invariants on the YAML (kind↔params variant matching) live in
 * [StatusEffectYamlConfig.toSpec] — if a YAML violates them, `toSpec` throws
 * and `ContentLoader` counts a failed load without breaking the batch.
 */
public class StatusContents : YamlDynamicContents<StatusEffectId, StatusEffectSpec, StatusEffectYamlConfig>(
    categoryName = "status",
    deserializer = StatusEffectYamlConfig.serializer(),
), IStatusEffectRegistry {

    /**
     * Convert each YAML DTO to its `(StatusEffectId, StatusEffectSpec)` entry.
     * Delegates to [StatusEffectYamlConfig.toSpec] so any future schema
     * evolution stays single-sourced in the DTO.
     */
    override fun toEntry(config: StatusEffectYamlConfig): Pair<StatusEffectId, StatusEffectSpec> {
        val spec = config.toSpec()
        return spec.id to spec
    }

    /**
     * Convenience overload: register by spec alone (the spec owns its id).
     * Used by test harnesses and admin commands that build a `StatusEffectSpec`
     * directly in memory.
     */
    public fun register(spec: StatusEffectSpec) {
        register(spec.id, spec)
    }
}
