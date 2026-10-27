package com.astarworks.astera.core.application.port.outbound.item

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Supplies the "what does this skill do" lines of an item tooltip. Core owns the tooltip layout
 * ([com.astarworks.astera.core.application.service.item.ItemTooltip]) but the skill and status
 * vocabulary lives in Combat, so Combat implements this port and attaches it to the shared
 * `ItemBlueprintFactory` at enable time. Absent (Combat not loaded) => tooltips show no effect lines.
 *
 * Every string is already rendered for [viewer]'s locale (MiniMessage).
 */
public interface ISkillTooltipSource {
    /**
     * @param cooldownOverrideTicks the item binding's own cooldown (wins over the skill's), or null.
     * @param compact true when the item binds many skills: omit the description line to keep the card short.
     */
    public fun brief(
        skillId: String,
        viewer: PlayerId?,
        cooldownOverrideTicks: Int? = null,
        compact: Boolean = false,
    ): SkillTooltipBrief?

    /** Flat damage reduction (0.0-1.0) of the armor piece with content id [pieceId], or null when unknown. */
    public fun armorReduction(pieceId: String): Double? = null
}

/**
 * @property name the skill's localized display name (shown after the input label).
 * @property lines at most two short effect lines (description, numbers), each already indented.
 * @property statusLines one plain-words line per status the skill applies ("Bleeding: ...").
 * @property usesEgo the skill spends the E.G.O gauge.
 */
public data class SkillTooltipBrief(
    val name: String,
    val lines: List<String>,
    val statusLines: List<String> = emptyList(),
    val usesEgo: Boolean = false,
)
