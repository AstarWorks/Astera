package com.astarworks.astera.domain.model.skill

import com.astarworks.astera.domain.model.i18n.MessageKey
import com.astarworks.astera.domain.model.skill.params.SkillParams

/**
 * Immutable skill definition.
 *
 * The YAML in `content/skills/` is deserialized (in the application layer) into
 * [SkillSpec] and registered with an `ISkillRegistry`. `params` is a typed
 * sealed hierarchy ([SkillParams]) so dispatch happens via `when` without
 * reflection — see `principles.md` §12.5.
 *
 * `archetype` and `params` are independently validated here; the application
 * layer enforces that they pair consistently (e.g. `PROJECTILE` archetype
 * carries [SkillParams.ProjectileParams]).
 */
public data class SkillSpec(
    val id: SkillId,
    val displayNameKey: MessageKey,
    val descriptionKey: MessageKey,
    val archetype: SkillArchetype,
    val trigger: SkillTrigger,
    val cooldownTicks: Int,
    val params: SkillParams,
) {
    init {
        require(cooldownTicks >= 0) { "cooldownTicks must be >= 0: $cooldownTicks" }
    }
}
