package com.astarworks.astera.combat.domain.skill

import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.skill.SkillId

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
 *
 * **Wave _items-1 (ADR-0073)**: `trigger` was removed — input binding now
 * lives on `ItemSpec.bindings` (a skill can be bound to multiple triggers per
 * item, or to none at all if invoked by the system / scheduled fires). Display
 * name and description are stored inline as [LocalizedText] (a per-locale
 * map) rather than `MessageKey`, so each skill YAML is self-contained and
 * does not require a sibling write into `content/languages/<locale>.yaml`.
 */
public data class SkillSpec(
    val id: SkillId,
    val name: LocalizedText,
    val description: LocalizedText,
    val archetype: SkillArchetype,
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
