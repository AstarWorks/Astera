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
    /**
     * Optional mana cost. `null` = free to cast.
     * Wave 5a: consumed by `ConsumeManaUseCase` before `SkillCast` is published.
     */
    val manaCost: Long? = null,
    /**
     * Hold-to-cast behavior. Defaults to instant for backward-compatibility
     * with skills written before Wave 5a.
     */
    val chargeVariant: ChargedSkillVariant = ChargedSkillVariant.Instant,
) {
    init {
        require(cooldownTicks >= 0) { "cooldownTicks must be >= 0: $cooldownTicks" }
        manaCost?.let { require(it >= 0) { "manaCost must be >= 0 when set: $it" } }
    }
}
