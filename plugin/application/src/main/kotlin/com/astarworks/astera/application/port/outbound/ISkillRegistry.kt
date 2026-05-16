package com.astarworks.astera.application.port.outbound

import com.astarworks.astera.domain.model.skill.SkillId
import com.astarworks.astera.domain.model.skill.SkillSpec

/**
 * Read-only registry of all known skills.
 *
 * Populated at startup by the same `ContentLoader<SkillYamlConfig>` pattern
 * that powers `IWeaponRegistry` — see [IContentParser] / `ContentLoader`.
 * The skill YAML schema lands in Phase 2 mid implementation; this port is
 * declared now so use cases written against it stay agnostic.
 */
public interface ISkillRegistry {
    public fun find(id: SkillId): SkillSpec?
    public fun all(): Collection<SkillSpec>
}
