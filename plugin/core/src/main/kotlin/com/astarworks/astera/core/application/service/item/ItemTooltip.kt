package com.astarworks.astera.core.application.service.item

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemAttributes
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceType
import java.util.Locale

/**
 * The one place an [ItemSpec] becomes player-facing lore (ADR-0211).
 *
 * Every surface that shows an item - the inventory stack (`PaperItemMaterializer`), shop / vault /
 * admin GUI icons (`toAdapterTemplate`) - calls [lore], so rarity, level and stats can never drift from
 * the spec or from each other. The YAML `lore:` only carries flavor / usage text; it must not repeat
 * rarity, level or numbers (those lines were hand-written and went stale).
 *
 * Layout: header (rarity + level), the spec's own lore, then derived stat lines in a fixed order.
 */
public class ItemTooltip(private val messages: IMessageRenderer) {

    public fun lore(spec: ItemSpec, viewer: PlayerId?): List<String> {
        if (!isGear(spec)) {
            return if (spec.lore == LocalizedLore.EMPTY) emptyList() else messages.renderLocalizedLore(viewer, spec.lore)
        }
        return gearLore(spec, viewer)
    }

    /** Gameplay items (skills, stats or a resource pool). GUI panes / indicators / tiles are plain. */
    public fun isGear(spec: ItemSpec): Boolean =
        spec.skillBindings.isNotEmpty() || spec.attributes != ItemAttributes.EMPTY || spec.resourceType != null

    private fun gearLore(spec: ItemSpec, viewer: PlayerId?): List<String> = buildList {
        add(
            messages.render(
                viewer,
                HEADER,
                mapOf(
                    "rarity" to messages.render(viewer, rarityKey(spec.rarity)),
                    "level" to spec.levelRequirement.toString(),
                ),
            ),
        )
        if (spec.lore != LocalizedLore.EMPTY) addAll(messages.renderLocalizedLore(viewer, spec.lore))
        addAll(statLines(spec, viewer))
    }

    /** Derived numeric lines only (no header / flavor), exposed for tests and compact surfaces. */
    public fun statLines(spec: ItemSpec, viewer: PlayerId?): List<String> {
        val a = spec.attributes
        val stats = listOfNotNull(
            a.attackDamage?.let { "attack_damage" to it },
            a.attackSpeed?.let { "attack_speed" to it },
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

    private fun rarityKey(rarity: Rarity): MessageKey = MessageKey("$BASE.rarity.${rarity.name.lowercase()}")

    private fun number(value: Double): String =
        if (value == Math.rint(value)) value.toLong().toString() else String.format(Locale.ROOT, "%.1f", value)

    private companion object {
        const val BASE = "astera.item.tooltip"
        const val STAT = "astera.item.tooltip.stat"
        const val PERCENT = 100.0
        val HEADER = MessageKey("astera.item.tooltip.header")
    }
}
