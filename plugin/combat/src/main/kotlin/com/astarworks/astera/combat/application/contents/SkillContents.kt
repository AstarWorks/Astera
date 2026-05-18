package com.astarworks.astera.combat.application.contents

import com.astarworks.astera.combat.application.config.SkillYamlConfig
import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.domain.skill.SkillId
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.core.application.contents.YamlDynamicContents

/**
 * Skill-side `Contents<SkillId, SkillSpec>` (Wave _infra-2b, ADR-0065).
 *
 * Replaces the legacy `MutableSkillRegistry` + `SkillLoaderService` pair with
 * a single instance that owns an atomic-swap store and participates in the
 * cross-plugin reload registry.
 *
 * Inherits:
 *
 *   - `find(id) / all()` from [com.astarworks.astera.core.application.contents.Contents]
 *     (the `ISkillRegistry` port surface that every skill use case consumes)
 *   - `bootstrap(dir)` for `onEnable` initial load (best-effort)
 *   - `reload(dir)` for `/astera reload skills` (all-or-nothing, atomic swap)
 *   - `register(id, spec) / clear()` for tests and programmatic registration
 *
 * Cross-field invariants on the YAML (archetype↔params variant matching) live
 * in [SkillYamlConfig.toSpec] — if a YAML file violates them, `toSpec` throws
 * and `ContentLoader` counts a failed load without breaking the batch.
 */
public class SkillContents : YamlDynamicContents<SkillId, SkillSpec, SkillYamlConfig>(
    categoryName = "skills",
    deserializer = SkillYamlConfig.serializer(),
), ISkillRegistry {

    /**
     * Convert each YAML DTO to its `(SkillId, SkillSpec)` entry. Delegates to
     * [SkillYamlConfig.toSpec] so any future schema evolution stays
     * single-sourced in the DTO.
     */
    override fun toEntry(config: SkillYamlConfig): Pair<SkillId, SkillSpec> {
        val spec = config.toSpec()
        return spec.id to spec
    }

    /**
     * Convenience overload: register by spec alone (the spec owns its id).
     * Used by test harnesses and admin commands that build a `SkillSpec`
     * directly in memory.
     */
    public fun register(spec: SkillSpec) {
        register(spec.id, spec)
    }
}
