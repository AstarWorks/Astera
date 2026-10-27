package com.astarworks.astera.core.application.service.item

import com.astarworks.astera.core.application.port.outbound.IMatchLevelGate
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.item.ISkillTooltipSource
import com.astarworks.astera.core.application.port.outbound.item.SkillTooltipBrief
import com.astarworks.astera.core.application.usecase.block.HitCustomBlockUseCase
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ARMOR_PIECE_TAG
import com.astarworks.astera.core.domain.item.ItemAttributes
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceType
import java.util.Locale

/**
 * The one place an [ItemSpec] becomes player-facing lore (ADR-0211). Notation rules:
 * `docs/contributing/item-lore-style.md`.
 *
 * Every surface that shows an item - the inventory stack (`PaperItemMaterializer`), shop / vault /
 * admin GUI icons (the same stack, ADR-0241) - gets it via `ItemBlueprintFactory`, which calls [lore], so rarity,
 * skills and stats can never drift from the spec or from each other. The YAML only carries the one-line
 * `role` and an optional `flavor`; skill lines and numbers are generated.
 *
 * Card layout (sections separated by one blank line): header (rarity + level) and role; one block per
 * skill binding (input label, skill name, description, numbers) plus status / E.G.O notes; the stats block
 * in a fixed order (and the Norma lock line); flavour. Items that are not gear (blocks, ores) show only
 * role and flavour; `gui-*` tiles keep their plain `lore:`.
 */
public class ItemTooltip(
    private val messages: IMessageRenderer,
    /** Resolved lazily: Match may register after Core builds the tooltip. `null` result = no Norma gate. */
    private val levelGate: () -> IMatchLevelGate? = { null },
    private val skillBriefs: () -> ISkillTooltipSource? = { null },
) {

    public fun lore(spec: ItemSpec, viewer: PlayerId?): List<String> {
        if (!usesCard(spec)) {
            return if (spec.lore == LocalizedLore.EMPTY) emptyList() else messages.renderLocalizedLore(viewer, spec.lore)
        }
        return card(spec, viewer)
    }

    /** Gameplay items (skills, stats, a resource pool, a field tool or an armor piece). GUI panes / indicators / tiles are plain. */
    public fun isGear(spec: ItemSpec): Boolean =
        spec.skillBindings.isNotEmpty() || spec.attributes != ItemAttributes.EMPTY || spec.resourceType != null ||
            HitCustomBlockUseCase.TOOL_TAG in spec.pdcTags || ARMOR_PIECE_TAG in spec.pdcTags

    /** Gear and any item that declares a `role` is shown as a structured card; the rest keep plain `lore:`. */
    public fun usesCard(spec: ItemSpec): Boolean = isGear(spec) || spec.role != LocalizedText.EMPTY

    /** `<rarity colour>name</...>` unless the name already carries its own colour (Limbus persona weapons). */
    public fun colouredName(name: String, rarity: Rarity): String =
        if (name.contains('<') || name.contains('&')) name else "<${rarityTag(rarity)}>$name</${rarityTag(rarity)}>"

    private fun card(spec: ItemSpec, viewer: PlayerId?): List<String> {
        val gear = isGear(spec)
        val head = buildList {
            if (gear) add(header(spec, viewer))
            text(spec.role, viewer)?.let { add("<gray>${TooltipWidth.fit(it)}</gray>") }
        }
        val flavor = text(spec.flavor, viewer)?.let { listOf("<dark_gray><italic>${TooltipWidth.fit(it)}</italic></dark_gray>") }.orEmpty()
        val sections = if (gear) listOf(head, skillLines(spec, viewer), statLines(spec, viewer) + lockedLines(spec, viewer), flavor) else listOf(head, flavor)
        return sections.filter { it.isNotEmpty() }.flatMapIndexed { i, part -> if (i == 0) part else listOf("") + part }
    }

    private fun header(spec: ItemSpec, viewer: PlayerId?): String {
        val tag = rarityTag(spec.rarity)
        val rarity = "<$tag>${messages.render(viewer, rarityKey(spec.rarity))}</$tag>"
        return messages.render(viewer, HEADER, mapOf("rarity" to rarity, "level" to spec.levelRequirement.toString()))
    }

    private fun text(value: LocalizedText, viewer: PlayerId?): String? =
        if (value == LocalizedText.EMPTY) null else messages.renderText(viewer, value).trim().takeIf { it.isNotEmpty() }

    /**
     * One block per skill binding, in binding order: `[input] Skill name`, then the generated effect lines
     * of that skill; then one plain line per applied status and, when a skill spends the gauge, what E.G.O is.
     * An item binding [COMPACT_FROM] or more skills is compact (no description / status lines).
     */
    private fun skillLines(spec: ItemSpec, viewer: PlayerId?): List<String> {
        if (spec.skillBindings.isEmpty()) return emptyList()
        val source = skillBriefs()
        val compact = spec.skillBindings.size >= COMPACT_FROM
        val briefs = mutableListOf<SkillTooltipBrief>()
        val out = mutableListOf<String>()
        for (binding in spec.skillBindings) {
            val brief = source?.brief(binding.skillId.value, viewer, binding.cooldownOverrideTicks, compact)
            brief?.let { briefs += it }
            val name = TooltipWidth.fit(brief?.name?.takeIf { it.isNotBlank() } ?: binding.skillId.value, NAME_PX)
            out += messages.render(viewer, MessageKey("$BASE.trigger.${binding.trigger.name.lowercase()}"), mapOf("name" to name))
            brief?.lines?.let { out += it }
        }
        if (!compact) out += briefs.flatMap { it.statusLines }.distinct()
        if (briefs.any { it.usesEgo }) out += messages.render(viewer, EGO_NOTE)
        return out
    }

    /** One greyed line when the item's rarity opens above Norma level 1 (ADR-0292): usable only from that in-match level. */
    private fun lockedLines(spec: ItemSpec, viewer: PlayerId?): List<String> {
        val required = levelGate()?.requiredLevel(spec.rarity) ?: return emptyList()
        if (required <= 1 || spec.skillBindings.isEmpty()) return emptyList()
        return listOf(messages.render(viewer, MessageKey("$BASE.locked"), mapOf("level" to required.toString())))
    }

    /** Derived numeric lines only (no header / flavor), in the fixed stats-block order. */
    public fun statLines(spec: ItemSpec, viewer: PlayerId?): List<String> {
        val a = spec.attributes
        val reduction = spec.pdcTags[ARMOR_PIECE_TAG]?.let { skillBriefs()?.armorReduction(it) }
        val stats = listOfNotNull(
            a.attackDamage?.let { "attack_damage" to it },
            a.attackSpeed?.let { "attack_speed" to it },
            reduction?.let { "damage_reduction" to it * PERCENT },
            a.armor?.let { "armor" to it },
            a.armorToughness?.let { "armor_toughness" to it },
            a.maxHealthBonus?.let { "max_health" to it },
            a.knockbackResistance?.let { "knockback_resistance" to it * PERCENT },
            a.movementSpeedMultiplier?.let { "move_speed" to it * PERCENT },
        )
        return buildList {
            stats.forEach { (id, value) ->
                add(messages.render(viewer, MessageKey("$STAT.$id"), mapOf("value" to number(value))))
            }
            toolLine(spec, viewer)?.let { add(it) }
            val type = spec.resourceType
            if (type != null) {
                if (type == ResourceType.Ammo && spec.resourceMaxPool > 0) {
                    add(messages.render(viewer, MessageKey("$STAT.magazine"), mapOf("value" to spec.resourceMaxPool.toString())))
                }
                if (spec.resourceCostPerUse > 0) {
                    val token = if (type == ResourceType.Ammo) "ammo" else "energy"
                    add(
                        messages.render(
                            viewer,
                            MessageKey("$STAT.cost_$token"),
                            mapOf("value" to spec.resourceCostPerUse.toString()),
                        ),
                    )
                }
            }
        }
    }

    /** `Tool: Pickaxe (Diamond)` from the `astera:tool` tag (`KIND/TIER`). */
    private fun toolLine(spec: ItemSpec, viewer: PlayerId?): String? {
        val parts = spec.pdcTags[HitCustomBlockUseCase.TOOL_TAG]?.split('/') ?: return null
        if (parts.size != 2) return null
        val kind = messages.render(viewer, MessageKey("$BASE.tool.kind.${parts[0].lowercase()}"))
        val tier = messages.render(viewer, MessageKey("$BASE.tool.tier.${parts[1].lowercase()}"))
        return messages.render(viewer, MessageKey("$STAT.tool"), mapOf("kind" to kind, "tier" to tier))
    }

    private fun rarityKey(rarity: Rarity): MessageKey = MessageKey("$BASE.rarity.${rarity.name.lowercase()}")

    private fun number(value: Double): String =
        if (value == Math.rint(value)) value.toLong().toString() else String.format(Locale.ROOT, "%.1f", value)

    public companion object {
        private const val BASE = "astera.item.tooltip"
        private const val STAT = "astera.item.tooltip.stat"
        private const val PERCENT = 100.0
        private const val COMPACT_FROM = 4

        /** Room left for a skill name after its input label. */
        private const val NAME_PX = 150
        private val HEADER = MessageKey("astera.item.tooltip.header")
        private val EGO_NOTE = MessageKey("astera.skillbrief.ego_note")

        /**
         * The one rarity colour table (MiniMessage named colours, vanilla values): name, header rarity word and
         * the tooltip frame accent. Hex: white #FFFFFF, green #55FF55, aqua #55FFFF, light_purple #FF55FF, gold #FFAA00.
         */
        public fun rarityTag(rarity: Rarity): String = when (rarity) {
            Rarity.COMMON -> "white"
            Rarity.UNCOMMON -> "green"
            Rarity.RARE -> "aqua"
            Rarity.EPIC -> "light_purple"
            Rarity.LEGENDARY -> "gold"
        }
    }
}
