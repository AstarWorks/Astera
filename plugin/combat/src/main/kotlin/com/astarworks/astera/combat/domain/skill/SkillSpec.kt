package com.astarworks.astera.combat.domain.skill

import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.domain.i18n.MessageKey

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
