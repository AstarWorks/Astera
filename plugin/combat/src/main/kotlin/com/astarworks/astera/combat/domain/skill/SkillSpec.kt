package com.astarworks.astera.combat.domain.skill

import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.core.domain.skill.SkillId

/**
 * The cast gate's record of one skill (ADR-0301/0303): the numbers `CastSkillUseCase` checks and the text the
 * admin panel and tooltips show. It is built from the skill's `meta:` block by `SkillMetaRegistry`; nothing
 * executes from it (the v3 `ExpansionTable` / `TablePlayer` runs the skill).
 *
 *  - [name] / [description] - inline localized text, no `MessageKey`.
 *  - [tags] - free-form designer-facing classifiers (see [SkillTag]).
 *  - [resourceType] / [resourceCost] - what a cast spends from the caster's resource pool (ammo / energy).
 *    `resourceType == null` means "the resource of the item the cast comes from"; `resourceCost == null` means
 *    "the item's `resource_cost_per_use`"; an explicit `0` is free.
 *  - [egoCost] - E.G.O gauge points (`0..100`) the cast consumes (ADR-0231); the cast is refused when the gauge
 *    holds less. `null` / `0` = free.
 *  - [reloadTicks] - non-null marks a reload skill: [reloadTicks] after the cast the source item's ammo is
 *    refilled, and ammo casts are rejected meanwhile.
 *  - [readyCue] - play the "ready again" cue when the cooldown ends (ADR-0197); `null` = automatic (cooldowns of
 *    3 s or more).
 */
public data class SkillSpec(
    val id: SkillId,
    val name: LocalizedText,
    val description: LocalizedText,
    val cooldownTicks: Int,
    val tags: Set<SkillTag> = emptySet(),
    val resourceType: ResourceType? = null,
    val resourceCost: Int? = null,
    val reloadTicks: Int? = null,
    val readyCue: Boolean? = null,
    val egoCost: Int? = null,
) {
    init {
        require(cooldownTicks >= 0) { "cooldownTicks must be >= 0: $cooldownTicks" }
        resourceCost?.let { require(it >= 0) { "resourceCost must be >= 0 when set: $it" } }
        egoCost?.let { require(it >= 0) { "egoCost must be >= 0 when set: $it" } }
        reloadTicks?.let { require(it > 0) { "reloadTicks must be > 0 when set: $it" } }
    }
}
