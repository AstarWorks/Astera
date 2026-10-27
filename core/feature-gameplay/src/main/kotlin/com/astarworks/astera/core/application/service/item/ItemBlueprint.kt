package com.astarworks.astera.core.application.service.item

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.cooldown.ItemCooldownSweep
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.DurabilityPolicy
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemAttributes
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Everything the player sees and the server tags on ONE Astera item, resolved for one viewer
 * (ADR-0241). The Paper `PaperItemMaterializer` applies a blueprint verbatim to a Bukkit stack,
 * and it is the only code that does: the item in the inventory (`/astera items give`, shop, rewards),
 * the admin catalog / shop / kit / menu icons and the skill-tester item are all that stack, so name,
 * lore, `item_model`, glint and flags can never drift between "the icon" and "the item".
 *
 * GUI surfaces add their own extras (price, stock, click hints) as appended lore on top of it.
 */
public data class ItemBlueprint(
    val itemId: String,
    /** Vanilla material name; an external template (no provider adapter yet) falls back to `STONE`. */
    val material: String,
    val externalKey: String?,
    val customModelData: Int?,
    /** `astera:<id>` when the pack has generated art for the item (ADR-0193), else null. */
    val itemModel: String?,
    /** Rendered MiniMessage name, or null to keep the material's own. */
    val displayName: String?,
    val lore: List<String>,
    val glint: Boolean,
    val unbreakable: Boolean,
    val maxDurability: Int?,
    /** `use_cooldown` group (ADR-0197); only items that bind a skill have one. */
    val cooldownGroup: String?,
    val attributes: ItemAttributes,
    val equipSlot: EquipSlot,
    val enchantable: Boolean,
    val keepOnDeath: Boolean,
    val pdcTags: Map<String, String>,
) {
    public companion object {
        public const val FALLBACK_MATERIAL: String = "STONE"
    }
}

/** The single [ItemSpec] -> [ItemBlueprint] function (ADR-0241). */
public class ItemBlueprintFactory(
    private val messages: IMessageRenderer,
    private val itemModels: ItemModelCatalog = ItemModelCatalog.NONE,
    levelGate: () -> com.astarworks.astera.core.application.port.outbound.IMatchLevelGate? = { null },
) {
    /** Attached by Combat at enable time; null until then (tooltips just omit the effect lines). */
    @Volatile
    public var skillTooltips: com.astarworks.astera.core.application.port.outbound.item.ISkillTooltipSource? = null

    private val tooltip = ItemTooltip(messages, levelGate) { skillTooltips }

    public fun build(spec: ItemSpec, viewer: PlayerId?): ItemBlueprint {
        val template = spec.template
        val name = if (spec.displayName == LocalizedText.EMPTY) null else messages.renderText(viewer, spec.displayName).takeIf { it.isNotBlank() }?.let { tooltip.colouredName(it, spec.rarity) }
        val durability: DurabilityPolicy = spec.durability
        return ItemBlueprint(
            itemId = spec.id.value,
            material = (template as? ItemTemplate.Vanilla)?.material ?: ItemBlueprint.FALLBACK_MATERIAL,
            externalKey = (template as? ItemTemplate.External)?.externalKey,
            customModelData = (template as? ItemTemplate.Vanilla)?.customModelData,
            itemModel = itemModels.modelKey(spec.id.value),
            displayName = name?.takeIf { it.isNotBlank() },
            lore = tooltip.lore(spec, viewer),
            glint = spec.glint == true,
            unbreakable = durability.unbreakable,
            maxDurability = durability.maxDurability,
            cooldownGroup = if (spec.skillBindings.isNotEmpty()) ItemCooldownSweep.groupKey(spec.id) else null,
            attributes = spec.attributes,
            equipSlot = spec.equipSlot,
            enchantable = spec.enchantable,
            keepOnDeath = spec.dropPolicy.keepOnDeath,
            pdcTags = spec.pdcTags,
        )
    }

    /** Re-read the generated model list so art pushed after boot is seen (F3-9). */
    public fun rescanModels(): Unit = itemModels.rescan()
}
