package com.astarworks.astera.combat.domain.skill

import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.resource.ResourceType
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
 *  - [resourceType] / [resourceCost] — what a cast spends from the caster's
 *    resource pool (ammo / energy). `resourceType == null` means "the
 *    resource of the item the cast comes from"; `resourceCost == null` means
 *    "the item's `resource_cost_per_use`"; an explicit `0` is free.
 *  - [reloadTicks] — non-null marks a reload skill: [reloadTicks] after the cast
 *    the source item's ammo is refilled, and ammo casts are rejected meanwhile.
 *  - [chargeVariant] — hold-to-cast behaviour.
 *  - [readyCue] — play the "ready again" cue when the cooldown ends (ADR-0197);
 *    `null` = automatic (cooldowns of 3 s or more).
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
    val resourceType: ResourceType? = null,
    val resourceCost: Int? = null,
    val reloadTicks: Int? = null,
    val chargeVariant: ChargedSkillVariant = ChargedSkillVariant.Instant,
    val readyCue: Boolean? = null,
) {
    init {
        require(cooldownTicks >= 0) { "cooldownTicks must be >= 0: $cooldownTicks" }
        resourceCost?.let { require(it >= 0) { "resourceCost must be >= 0 when set: $it" } }
        reloadTicks?.let { require(it > 0) { "reloadTicks must be > 0 when set: $it" } }
    }
}
