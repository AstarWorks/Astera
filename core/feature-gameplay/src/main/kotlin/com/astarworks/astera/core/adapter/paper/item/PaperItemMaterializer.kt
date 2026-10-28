package com.astarworks.astera.core.adapter.paper.item

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.service.item.ItemBlueprint
import com.astarworks.astera.core.application.service.item.ItemBlueprintFactory
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemAttributes
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import io.papermc.paper.datacomponent.DataComponentType
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.TooltipDisplay
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
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
 * The single place Astera turns an [ItemSpec] into a Bukkit stack (ADR-0241): the inventory item
 * (`/astera items give`, shop, rewards) and every GUI icon (admin catalog, shop offers, kit, menus)
 * come out of [buildFor]; GUI surfaces only append their own lore lines afterwards. What the stack
 * carries is decided by [ItemBlueprintFactory]; this class only applies it. Applies:
 *
 *  - Vanilla material resolution via `Material.matchMaterial`, falling back
 *    to `STONE` with a warning on miss.
 *  - Display name + lore rendered through the player's locale (uses the
 *    spec's inline [com.astarworks.astera.core.domain.i18n.LocalizedText] /
 *    [com.astarworks.astera.core.domain.i18n.LocalizedLore], or i18n keys
 *    via the message renderer).
 *  - PDC: writes `astera:item_id` (string) unconditionally so the resolver +
 *    enchant-gate can identify the stack later. Designer-supplied PDC tags
 *    on the spec's template are merged in.
 *  - `custom_model_data` for resource-pack-driven visuals, and the `item_model` component
 *    (`astera:<id>`) for items the pack has generated art for (see [com.astarworks.astera.core.application.service.item.ItemModelCatalog]).
 *  - `tooltip_style` (`astera:<rarity>`) for every Astera stack, including GUI icons.
 *  - [ItemAttributes] mapped to Bukkit `AttributeModifier`s, scoped to the
 *    spec's [EquipSlot].
 *  - [com.astarworks.astera.core.domain.item.DurabilityPolicy] — `unbreakable`
 *    flag and `maxDurability` override (the underlying NBT `Damage` is left
 *    at 0; max is enforced by Damageable).
 *  - Enchantment glint via a hidden `LUCK` enchantment when the spec asks for
 *    a glint with no actual enchantments to render.
 *
 * External templates (Oraxen / ItemAdder / …) fall back to `STONE` in
 * Phase 1. The provider-specific adapters land in Phase 4.
 *
 * Konsist allows Bukkit imports here (`adapter/paper`); the broader spec-side
 * type lives in `core.domain.item`.
 */
public class PaperItemMaterializer(
    private val plugin: Plugin,
    private val blueprints: ItemBlueprintFactory,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val itemIdKey: NamespacedKey = NamespacedKey(plugin, ITEM_ID_KEY)
    private val enchantableKey: NamespacedKey =
        NamespacedKey(plugin, ENCHANTABLE_KEY)
    private val keepOnDeathKey: NamespacedKey =
        NamespacedKey(plugin, KEEP_ON_DEATH_KEY)

    /**
     * Build a fresh ItemStack for [spec] sized to [amount], with display name +
     * lore rendered for [viewer]'s locale.
     */
    public fun build(spec: ItemSpec, viewer: IMcPlayer, amount: Int): ItemStack {
        return buildFor(spec, viewer.id, amount)
    }

    /**
     * Same as [build] but used when the spec is delivered through a path that
     * doesn't have an `IMcPlayer` handy (e.g. a server-side give that resolves
     * the player by id). The rendered locale falls back to the spec's primary
     * inline locale or the default.
     */
    public fun buildFor(spec: ItemSpec, viewerId: PlayerId?, amount: Int): ItemStack {
        val blueprint = blueprints.build(spec, viewerId)
        val stack = baseStack(blueprint, amount)
        applyMeta(stack, blueprint)
        stack.editMeta { it.tooltipStyle = NamespacedKey("astera", spec.rarity.name.lowercase()) }
        hideVanillaMetadata(stack)
        return stack
    }

    /** Re-scan the generated item models so [refreshVisuals] sees art pushed after boot. */
    public fun rescanModels(): Unit = blueprints.rescanModels()

    /**
     * Re-apply the visual half of [spec] (display name, lore, models and tooltip style) to an
     * existing stack in place (F3-9, ADR-0224). Everything per-instance (refinement, enchants,
     * damage, PDC state) and the base material stay untouched, so a held item picks up an edited
     * item YAML text or new art without being re-given. Returns whether anything changed.
     */
    public fun refreshVisuals(stack: ItemStack, spec: ItemSpec, viewerId: PlayerId?): Boolean {
        val fresh = buildFor(spec, viewerId, 1).itemMeta ?: return false
        val before = stack.itemMeta
        stack.editMeta { meta ->
            meta.displayName(fresh.displayName())
            meta.lore(fresh.lore())
            meta.tooltipStyle = fresh.tooltipStyle
            meta.setEquippable(if (fresh.hasEquippable()) fresh.equippable else null)
            meta.itemModel = if (fresh.hasItemModel()) fresh.itemModel else null
            @Suppress("DEPRECATION")
            if (fresh.hasCustomModelData()) meta.setCustomModelData(fresh.customModelData)
        }
        hideVanillaMetadata(stack)
        return stack.itemMeta != before
    }

    /**
     * Hides every vanilla metadata tooltip line (attributes, enchantments, durability, dye, trim, potion, ...)
     * through the `tooltip_display` component: every registered component type is hidden except the ones whose
     * lines Astera authors itself ([VISIBLE_COMPONENTS]). Future vanilla components are covered automatically.
     */
    private fun hideVanillaMetadata(stack: ItemStack) {
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hiddenComponents(hiddenComponents).build())
    }

    private val hiddenComponents: Set<DataComponentType> by lazy {
        RegistryAccess.registryAccess().getRegistry(RegistryKey.DATA_COMPONENT_TYPE).stream()
            .filter { it !in VISIBLE_COMPONENTS }
            .toList().toSet()
    }

    private fun baseStack(blueprint: ItemBlueprint, amount: Int): ItemStack {
        blueprint.externalKey?.let { key ->
            log.warn(
                "External template (key='{}') for item id={} - provider adapter not yet wired; falling back to STONE.",
                key, blueprint.itemId,
            )
        }
        val material: Material = Material.matchMaterial(blueprint.material) ?: run {
            log.warn("Unknown Material '{}' for item id={}; falling back to STONE", blueprint.material, blueprint.itemId)
            Material.STONE
        }
        val stackSize = amount.coerceAtLeast(1).coerceAtMost(material.maxStackSize)
        return ItemStack(material, stackSize)
    }

    @Suppress("LongMethod")
    private fun applyMeta(stack: ItemStack, blueprint: ItemBlueprint) {
        stack.editMeta { meta ->
            blueprint.displayName?.let { meta.displayName(upright(AsteraTextDeserializer.deserialize(it))) }
            if (blueprint.lore.isNotEmpty()) {
                meta.lore(blueprint.lore.map { upright(AsteraTextDeserializer.deserialize(it)) })
            }

            // PDC: the canonical astera:item_id, plus the policy flags the
            // enchant-gate and death-drop logic consult.
            val pdc = meta.persistentDataContainer
            pdc.set(itemIdKey, PersistentDataType.STRING, blueprint.itemId)
            pdc.set(enchantableKey, PersistentDataType.BYTE, if (blueprint.enchantable) 1 else 0)
            pdc.set(keepOnDeathKey, PersistentDataType.BYTE, if (blueprint.keepOnDeath) 1 else 0)

            blueprint.customModelData?.let {
                @Suppress("DEPRECATION")
                meta.setCustomModelData(it)
            }

            // item_model (ADR-0193): point at the pack's `astera:<id>` icon when it has one. The base
            // material stays, so a client without the pack still gets a normal vanilla item.
            blueprint.itemModel?.let { key ->
                NamespacedKey.fromString(key)?.let { meta.itemModel = it }
            }

            blueprint.equipmentAsset?.let { asset ->
                val equippable = meta.equippable
                equippable.slot = toBukkitSlot(blueprint.equipSlot)
                // Bukkit calls this model; the wire component field is minecraft:equippable.asset_id.
                equippable.model = NamespacedKey("astera", asset)
                meta.setEquippable(equippable)
            }

            // use_cooldown (ADR-0197): a per-item cooldown group so the skill cooldown / reload can
            // be drawn as the vanilla hotbar sweep (PaperItemCooldownSweepGateway). The 1-tick base
            // cooldown is the component's minimum; the real length is always set explicitly.
            blueprint.cooldownGroup?.let { raw ->
                NamespacedKey.fromString(raw)?.let { group ->
                    val useCooldown = meta.useCooldown
                    useCooldown.cooldownGroup = group
                    useCooldown.cooldownSeconds = BASE_COOLDOWN_SECONDS
                    meta.setUseCooldown(useCooldown)
                }
            }

            if (blueprint.unbreakable) {
                meta.isUnbreakable = true
                meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE)
            }
            val maxDurability = blueprint.maxDurability
            if (maxDurability != null && meta is Damageable) {
                // Bukkit doesn't let plugins override an item's vanilla max,
                // but it does let us set the current damage. We park the
                // desired max in PDC so future repair / display logic can
                // honor it.
                pdc.set(MAX_DURABILITY_KEY_NS(plugin), PersistentDataType.INTEGER, maxDurability)
            }

            applyAttributes(meta, blueprint.attributes, blueprint.equipSlot)

            // Glint without an enchantment: add a hidden enchantment. Skipped when the item already
            // carries any enchantment. `false` / unset leave vanilla behaviour.
            if (blueprint.glint && meta.enchants.isEmpty()) {
                @Suppress("DEPRECATION")
                meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true)
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
            }

            // Designer-supplied PDC tags from the spec. Convention: each key
            // is a fully-qualified `namespace:key`; entries that don't parse
            // are skipped with a warning rather than failing the give.
            for ((rawKey, rawValue) in blueprint.pdcTags) {
                val key = NamespacedKey.fromString(rawKey)
                if (key == null) {
                    log.warn("Item id={} has malformed pdcTag '{}'; skipping.", blueprint.itemId, rawKey)
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
        //
        // Wave 6a-redesign Step 3 (ADR-0151): the 5 combat-math vanilla
        // AttributeModifier writes (ATTACK_DAMAGE / ATTACK_SPEED / ARMOR /
        // ARMOR_TOUGHNESS / MAX_HEALTH) are REMOVED per ADR-0149 §F.
        // Astera owns damage / armor / HP math entirely — vanilla
        // AttributeModifier writes would feed vanilla Damageable.damage
        // math + Attribute.MAX_HEALTH which Astera's PaperDamageGateway /
        // PaperPlayerHealthGateway already supersede via container-backed
        // computation. Astera lore renderer surfaces the values as text
        // for player visibility (no vanilla MC tooltip dependency).
        //
        // KEPT modifiers:
        //  - KNOCKBACK_RESISTANCE: Wave 6a-1 ADR-0144 intentional
        //    data-channel; PaperKnockbackGateway reads back the aggregated
        //    Bukkit attribute value before scaling impulse.
        //  - MOVEMENT_SPEED: Astera setMultiplier (Wave 6a-4) last-writer-
        //    wins acceptable Phase 1 PoC; revisit if equip-time mods
        //    conflict with active gateway state.
        attrs.knockbackResistance?.let {
            addModifier(Attribute.KNOCKBACK_RESISTANCE, it, "astera.knockback_resistance")
        }
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

    /** Vanilla italicises custom names / lore; item text is upright unless it asks for italic itself (flavour). */
    private fun upright(c: net.kyori.adventure.text.Component): net.kyori.adventure.text.Component =
        c.decorationIfAbsent(net.kyori.adventure.text.format.TextDecoration.ITALIC, net.kyori.adventure.text.format.TextDecoration.State.FALSE)

    private fun toBukkitSlot(slot: EquipSlot): EquipmentSlot = when (slot) {
        EquipSlot.MAINHAND -> EquipmentSlot.HAND
        EquipSlot.OFFHAND -> EquipmentSlot.OFF_HAND
        EquipSlot.HELMET -> EquipmentSlot.HEAD
        EquipSlot.CHESTPLATE -> EquipmentSlot.CHEST
        EquipSlot.LEGGINGS -> EquipmentSlot.LEGS
        EquipSlot.BOOTS -> EquipmentSlot.FEET
    }

    public companion object {
        public const val ITEM_ID_KEY: String = "item_id"
        public const val ENCHANTABLE_KEY: String = "enchantable"
        public const val KEEP_ON_DEATH_KEY: String = "keep_on_death"
        private const val MAX_DURABILITY_KEY: String = "max_durability"
        /** Components whose tooltip lines are Astera's own text: name and lore (the frame is `tooltip_style`). */
        private val VISIBLE_COMPONENTS: Set<DataComponentType> = setOf(
            DataComponentTypes.CUSTOM_NAME, DataComponentTypes.ITEM_NAME, DataComponentTypes.LORE,
        )
        private const val BASE_COOLDOWN_SECONDS: Float = 0.05f

        @Suppress("FunctionName")
        private fun MAX_DURABILITY_KEY_NS(plugin: Plugin): NamespacedKey =
            NamespacedKey(plugin, MAX_DURABILITY_KEY)

    }
}
