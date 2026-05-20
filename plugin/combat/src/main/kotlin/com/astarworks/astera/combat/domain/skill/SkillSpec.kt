package com.astarworks.astera.combat.domain.skill

import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics

/**
 * Immutable skill definition.
 *
 * Wave _skills-2 (ADR-0077) reshapes the spec around the
 * Mechanic-Targeter-Phase architecture: `phases` carries the per-phase
 * lists of [com.astarworks.astera.core.domain.skill.mechanic.Mechanic]s the
 * dispatcher executes when the archetype's lifecycle reaches that phase.
 * The archetype-specific [SkillParams] still controls the macro shape
 * (projectile speed, AOE radius, etc.) while the phase-driven mechanics
 * are the declarative behavior.
 *
 * `phases` must be the [PhaseMechanics] subclass that matches `archetype`
 * (e.g. `PROJECTILE` ↔ `PhaseMechanics.Projectile`); the application layer
 * enforces that pairing when YAML is materialized.
 *
 *  - [name] / [description] — inline localized text, no `MessageKey`.
 *  - [archetype] / [params] — independently validated; pairing enforced by
 *    YAML loader.
 *  - [tags] — free-form designer-facing classifiers (see [SkillTag]).
 *  - [phases] — per-phase mechanic lists.
 *  - [manaCost] — `null` = free to cast.
 *  - [chargeVariant] — hold-to-cast behaviour.
 *
 * Per the Phase 1 pre-release posture, this is a hard break: no
 * `@Deprecated` alias of the old shape.
 */
public data class SkillSpec(
    val id: SkillId,
    val name: LocalizedText,
    val description: LocalizedText,
    val archetype: SkillArchetype,
    val cooldownTicks: Int,
    val params: SkillParams,
    val tags: Set<SkillTag> = emptySet(),
    val phases: PhaseMechanics,
    val manaCost: Long? = null,
    val chargeVariant: ChargedSkillVariant = ChargedSkillVariant.Instant,
) {
    init {
        require(cooldownTicks >= 0) { "cooldownTicks must be >= 0: $cooldownTicks" }
        manaCost?.let { require(it >= 0) { "manaCost must be >= 0 when set: $it" } }
    }
}
