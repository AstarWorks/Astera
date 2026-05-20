package com.astarworks.astera.items.adapter.paper

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemAttributes
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.minimessage.MiniMessage
import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * ItemSpec → Bukkit `ItemStack` materializer (Wave _items-1, ADR-0073).
 *
 * The single place Astera turns an [ItemSpec] into a stack that can live in
 * the player's inventory. Applies:
 *
 *  - Vanilla material resolution via `Material.matchMaterial`, falling back
 *    to `STONE` with a warning on miss.
 *  - Display name + lore rendered through the player's locale (uses the
 *    spec's inline [com.astarworks.astera.core.domain.i18n.LocalizedText] /
 *    [com.astarworks.astera.core.domain.i18n.LocalizedLore], or i18n keys
 *    via [IMessageRenderer] when the spec carries `displayNameKey`).
 *  - PDC: writes `astera:item_id` (string) unconditionally so the resolver +
 *    enchant-gate can identify the stack later. Designer-supplied PDC tags
 *    on the spec's template are merged in.
 *  - `custom_model_data` for resource-pack-driven visuals.
 *  - [ItemAttributes] mapped to Bukkit `AttributeModifier`s, scoped to the
 *    spec's [EquipSlot].
 *  - [com.astarworks.astera.core.domain.item.DurabilityPolicy] — `unbreakable`
 *    flag and `maxDurability` override (the underlying NBT `Damage` is left
 *    at 0; max is enforced by Damageable).
 *  - Enchantment glint via a hidden `LUCK` enchantment when the spec asks for
 *    a glint with no actual enchantments to render.
 *
 * External templates (Oraxen / ItemAdder / …) fall back to `STONE` in
 * Phase 1, mirroring `PaperPlayer.giveItem`'s existing posture. The
 * provider-specific adapters land in Phase 4.
 *
 * Konsist allows Bukkit imports here (`adapter/paper`); the broader spec-side
 * type lives in `core.domain.item`.
 */
public class PaperItemMaterializer(
    private val plugin: Plugin,
    private val messages: IMessageRenderer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val itemIdKey: NamespacedKey =
        NamespacedKey(plugin, PaperItemIdResolver.ITEM_ID_KEY)
    private val enchantableKey: NamespacedKey =
        NamespacedKey(plugin, ENCHANTABLE_KEY)
    private val keepOnDeathKey: NamespacedKey =
        NamespacedKey(plugin, KEEP_ON_DEATH_KEY)

    /**
     * Build a fresh ItemStack for [spec] sized to [amount], with display name +
     * lore rendered for [viewer]'s locale.
     */
    public fun build(spec: ItemSpec, viewer: IMcPlayer, amount: Int): ItemStack {
        val stack = baseStack(spec, amount)
        applyMeta(stack, spec, viewer.id, viewer.locale)
        return stack
    }

    /**
     * Same as [build] but used when the spec is delivered through a path that
     * doesn't have an `IMcPlayer` handy (e.g. a server-side give that resolves
     * the player by id). The rendered locale falls back to the spec's primary
     * inline locale or the default.
     */
    public fun buildFor(spec: ItemSpec, viewerId: PlayerId?, locale: String?, amount: Int): ItemStack {
        val stack = baseStack(spec, amount)
        applyMeta(stack, spec, viewerId, locale)
        return stack
    }

    private fun baseStack(spec: ItemSpec, amount: Int): ItemStack {
        val template = spec.template
        val material: Material = when (template) {
            is ItemTemplate.Vanilla -> Material.matchMaterial(template.material) ?: run {
                log.warn("Unknown Material '{}' for item id={}; falling back to STONE", template.material, spec.id)
                Material.STONE
            }
            is ItemTemplate.External -> {
                log.warn(
                    "External template (key='{}') for item id={} — provider adapter not yet wired; falling back to STONE.",
                    template.externalKey, spec.id,
                )
                Material.STONE
            }
        }
        val stackSize = amount.coerceAtLeast(1).coerceAtMost(material.maxStackSize)
        return ItemStack(material, stackSize)
    }

    @Suppress("LongMethod")
    private fun applyMeta(stack: ItemStack, spec: ItemSpec, viewerId: PlayerId?, locale: String?) {
        stack.editMeta { meta ->
            // Display name + lore (inline LocalizedText / LocalizedLore via
            // IMessageRenderer.renderText — the Wave _items-1 addition that
            // resolves inline locale maps with the same fallback + placeholder
            // pipeline as MessageKey-based labels).
            val displayName = messages.renderText(viewerId, spec.displayName)
            if (displayName.isNotBlank()) {
                meta.displayName(AsteraTextDeserializer.deserialize(displayName))
            }
            val lore = messages.renderLocalizedLore(viewerId, spec.lore)
            if (lore.isNotEmpty()) {
                meta.lore(lore.map { AsteraTextDeserializer.deserialize(it) })
            }
            // viewer locale is consulted inside the renderer; kept here for
            // forward-compat with any local fallback we may need pre-render.
            @Suppress("UNUSED_VARIABLE")
            val resolvedLocale = locale?.let(LocaleCode.Companion::parse) ?: LocaleCode.EN

            // PDC: the canonical astera:item_id, plus the policy flags the
            // enchant-gate and death-drop logic consult.
            val pdc = meta.persistentDataContainer
            pdc.set(itemIdKey, PersistentDataType.STRING, spec.id.value)
            pdc.set(enchantableKey, PersistentDataType.BYTE, if (spec.enchantable) 1 else 0)
            pdc.set(keepOnDeathKey, PersistentDataType.BYTE, if (spec.dropPolicy.keepOnDeath) 1 else 0)

            // External plugin tags (e.g. provider-specific PDC entries from
            // the spec's template). Vanilla templates may also surface
            // arbitrary string tags later — Phase 1 keeps the surface
            // minimal.

            // custom_model_data
            val template = spec.template
            if (template is ItemTemplate.Vanilla && template.customModelData != null) {
                @Suppress("DEPRECATION")
                meta.setCustomModelData(template.customModelData)
            }

            // Durability
            val durability = spec.durability
            if (durability.unbreakable) {
                meta.isUnbreakable = true
                meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE)
            }
            val maxDurability = durability.maxDurability
            if (maxDurability != null && meta is Damageable) {
                // Bukkit doesn't let plugins override an item's vanilla max,
                // but it does let us set the current damage. We park the
                // desired max in PDC so future repair / display logic can
                // honor it.
                pdc.set(MAX_DURABILITY_KEY_NS(plugin), PersistentDataType.INTEGER, maxDurability)
            }

            // Attributes → AttributeModifier
            applyAttributes(meta, spec.attributes, spec.equipSlot)

            // Glint without an enchantment: add a hidden LUCK enchantment.
            // Skipped when the item already carries any enchantment.
            //
            // `glint` is nullable (Boolean?): `null` = "no opinion, use vanilla
            // behaviour"; `true` = force a glint; `false` = explicitly suppress
            // (no-op in Phase 1 since vanilla won't add a glint without
            // enchantments anyway).
            if (spec.glint == true && meta.enchants.isEmpty()) {
                @Suppress("DEPRECATION")
                meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true)
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
            }

            // Designer-supplied PDC tags from the spec. Convention: each key
            // is a fully-qualified `namespace:key`; entries that don't parse
            // are skipped with a warning rather than failing the give.
            for ((rawKey, rawValue) in spec.pdcTags) {
                val key = NamespacedKey.fromString(rawKey)
                if (key == null) {
                    log.warn("ItemSpec id={} has malformed pdcTag '{}'; skipping.", spec.id, rawKey)
                    continue
                }
                pdc.set(key, PersistentDataType.STRING, rawValue)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyAttributes(
        meta: org.bukkit.inventory.meta.ItemMeta,
        attrs: ItemAttributes,
        equipSlot: EquipSlot,
    ) {
        val slot = toBukkitSlot(equipSlot)

        fun addModifier(attribute: Attribute, amount: Double, name: String) {
            val modifier = AttributeModifier(
                UUID.randomUUID(),
                name,
                amount,
                AttributeModifier.Operation.ADD_NUMBER,
                slot,
            )
            meta.addAttributeModifier(attribute, modifier)
        }

        // Paper 1.21+ dropped the GENERIC_ prefix on Attribute enum values.
        attrs.attackDamage?.let { addModifier(Attribute.ATTACK_DAMAGE, it, "astera.attack_damage") }
        attrs.attackSpeed?.let { addModifier(Attribute.ATTACK_SPEED, it, "astera.attack_speed") }
        attrs.armor?.let { addModifier(Attribute.ARMOR, it, "astera.armor") }
        attrs.armorToughness?.let { addModifier(Attribute.ARMOR_TOUGHNESS, it, "astera.armor_toughness") }
        attrs.knockbackResistance?.let {
            addModifier(Attribute.KNOCKBACK_RESISTANCE, it, "astera.knockback_resistance")
        }
        attrs.maxHealthBonus?.let { addModifier(Attribute.MAX_HEALTH, it, "astera.max_health") }
        attrs.movementSpeedMultiplier?.let { mult ->
            val modifier = AttributeModifier(
                UUID.randomUUID(),
                "astera.movement_speed",
                mult,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                slot,
            )
            meta.addAttributeModifier(Attribute.MOVEMENT_SPEED, modifier)
        }

        if (attrs != ItemAttributes.EMPTY) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
    }

    private fun toBukkitSlot(slot: EquipSlot): EquipmentSlot = when (slot) {
        EquipSlot.MAINHAND -> EquipmentSlot.HAND
        EquipSlot.OFFHAND -> EquipmentSlot.OFF_HAND
        EquipSlot.HELMET -> EquipmentSlot.HEAD
        EquipSlot.CHESTPLATE -> EquipmentSlot.CHEST
        EquipSlot.LEGGINGS -> EquipmentSlot.LEGS
        EquipSlot.BOOTS -> EquipmentSlot.FEET
    }

    public companion object {
        public const val ENCHANTABLE_KEY: String = "enchantable"
        public const val KEEP_ON_DEATH_KEY: String = "keep_on_death"
        private const val MAX_DURABILITY_KEY: String = "max_durability"

        @Suppress("FunctionName")
        private fun MAX_DURABILITY_KEY_NS(plugin: Plugin): NamespacedKey =
            NamespacedKey(plugin, MAX_DURABILITY_KEY)

        private val MM: MiniMessage = MiniMessage.miniMessage()
    }
}
