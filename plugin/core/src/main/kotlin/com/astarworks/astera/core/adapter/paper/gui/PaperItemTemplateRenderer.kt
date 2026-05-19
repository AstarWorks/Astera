package com.astarworks.astera.core.adapter.paper.gui

import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.service.toAdapterTemplate
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.ResolvedReactiveSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.minimessage.MiniMessage
import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.slf4j.LoggerFactory

/**
 * Materializes a declarative [GuiSlot] into a Paper [ItemStack] for one viewer.
 *
 * Per ADR-0021 (Wave 4 R3 Declarative GUI), the adapter side of the GUI
 * runtime turns `GuiSlot.Static / Border / Reactive` into Bukkit item stacks
 * with i18n-rendered display name + lore and a stable PDC tag identifying the
 * underlying [ItemId]. `GuiSlot.Empty` renders as `null` (i.e. no stack).
 *
 * # Wave 5f reactive overrides
 *
 * Reactive slots now resolve to [ResolvedReactiveSlot] (RTM #21), which can
 * override the static template's display name / lore / count. When any
 * override field is non-null we render the underlying item template, then
 * patch the resulting `ItemStack` with the override values. A `null` override
 * means "fall back to whatever the GUI YAML / item template would have
 * rendered statically".
 *
 * Why the logic is duplicated from `PaperPlayer.giveItem`:
 *   `PaperPlayer` owns "give this item to a player's inventory" (which writes
 *   into the player's hot-bar via `inventory.addItem`). The GUI renderer owns
 *   "produce a free-standing ItemStack the adapter will `setItem` into a
 *   custom inventory slot". Trying to share through a `buildItemStack`
 *   helper on `PaperPlayer` would force a refactor of `PaperPlayer.kt` that
 *   is currently the worker-β touch-point (Wave 4 Phase B). Worker α (this
 *   file) and worker β (the GuiStore + reactive loop) must not collide on
 *   `PaperPlayer.kt`; duplicating ~25 lines is the explicitly cheaper option
 *   per the Wave 4 plan.
 *
 * Reactive caveat:
 *   `GuiDiffEngine` ferries `BindingKey.value` through `ItemId(...)`. Bindings
 *   allow dots (`player.coins`); [ItemId] does not. To stop one typo from
 *   killing the entire render, [render] catches `IllegalArgumentException`
 *   from the `ItemId` constructor / [IItemRegistry.find] and falls
 *   back to a `Material.BARRIER` placeholder so the slot stays visible and
 *   the failure is grep-able in logs.
 *
 * Threading: invoked only from the server main thread (same constraint as
 * the surrounding [PaperGuiAdapter]).
 */
public class PaperItemTemplateRenderer(
    private val items: IItemRegistry,
    private val messages: IMessageRenderer,
    private val bindingResolver: com.astarworks.astera.core.application.port.outbound.IGuiBindingResolver,
) {

    /**
     * Render [slot] for the given [viewer]. Returns `null` when the slot is
     * [GuiSlot.Empty] — the adapter interprets that as "clear / leave blank".
     *
     * @param viewer the player whose locale the renderer should resolve
     *   `displayName` / `lore` for. Passing `null` falls back to the
     *   renderer's default locale (see `SimpleMessageRenderer`).
     */
    fun render(slot: GuiSlot, viewer: PlayerId?): ItemStack? = when (slot) {
        GuiSlot.Empty -> null
        is GuiSlot.Static -> resolve(slot.itemId, slot.count, viewer)
        is GuiSlot.Border -> resolve(slot.itemId, count = 1, viewer = viewer)
        is GuiSlot.Reactive -> resolveReactive(slot, viewer)
        // Wave 5h: render the confirm-button item; cancel button + prompt
        // bar are the input-listener layer's job (Wave 6b). Treated like a
        // Static for now to keep the existing renderer monoline.
        is GuiSlot.Confirmation -> resolve(slot.confirmItemId, count = 1, viewer = viewer)
        // Wave 5h: render a BARRIER placeholder. The Paper anvil-input
        // adapter (Wave 6b) swaps the inventory wholesale before this slot
        // ever reaches the player; the placeholder only matters if a non-
        // anvil container is used for testing.
        is GuiSlot.TextInput -> barrier()
    }

    /**
     * Reactive resolution via [bindingResolver]. Unknown bindings return null
     * → render BARRIER. Wave 5f returns [ResolvedReactiveSlot] so the resolver
     * can override display name / lore / count for numeric bindings
     * (`player.coins.star`, `player.kills`).
     */
    private fun resolveReactive(slot: GuiSlot.Reactive, viewer: PlayerId?): ItemStack {
        val resolved = bindingResolver.resolve(slot.binding, viewer)
        if (resolved == null) {
            log.warn(
                "Reactive binding '{}' did not resolve for viewer={}; rendering BARRIER.",
                slot.binding.value,
                viewer,
            )
            return barrier()
        }
        val base = resolve(resolved.itemId, count = resolved.countOverride ?: 1, viewer = viewer)
            ?: return barrier().also {
                log.warn("Reactive binding '{}' did not resolve to a registered item.", slot.binding.value)
            }
        return applyOverrides(base, resolved, viewer)
    }

    /**
     * Patch [stack] with any non-null override on [resolved]. The renderer
     * already set the count when calling [resolve]; this method only touches
     * meta (display name + lore). Returns the same stack instance (mutated).
     */
    private fun applyOverrides(
        stack: ItemStack,
        resolved: ResolvedReactiveSlot,
        viewer: PlayerId?,
    ): ItemStack {
        if (resolved.displayOverride == null && resolved.loreOverride == null) {
            return stack
        }
        stack.editMeta { meta ->
            resolved.displayOverride?.let { key ->
                meta.displayName(AsteraTextDeserializer.deserialize(messages.render(viewer, key)))
            }
            resolved.loreOverride?.let { keys ->
                meta.lore(keys.map { k -> AsteraTextDeserializer.deserialize(messages.render(viewer, k)) })
            }
        }
        return stack
    }

    /**
     * Look up [id] in the item registry and materialize it. Returns `null`
     * when the id is unknown so the caller can decide whether that is a
     * fatal error (BARRIER placeholder) or expected (Reactive miss).
     *
     * Per Wave _items-1 (ADR-0073), the registry returns
     * [com.astarworks.astera.core.domain.item.ItemSpec]; the adapter-boundary
     * template is produced by [toAdapterTemplate] which resolves the spec's
     * inline `LocalizedText` / `LocalizedLore` via [messages] before handing
     * off to the existing Vanilla / External branch.
     */
    private fun resolve(id: ItemId, count: Int, viewer: PlayerId?): ItemStack? {
        val spec = items.find(id) ?: run {
            log.warn("GUI slot referenced unknown ItemId '{}'.", id.value)
            return null
        }
        return when (val template = spec.toAdapterTemplate(messages, viewer)) {
            is IMcItemTemplate.Vanilla -> buildVanilla(template, count)
            is IMcItemTemplate.External -> {
                log.warn(
                    "External item template '{}' (key={}) — provider adapter not wired; falling back to STONE.",
                    template.id, template.key,
                )
                stamp(ItemStack(Material.STONE).also { it.amount = count.coerceIn(1, 64) }, template.id)
            }
        }
    }

    private fun buildVanilla(template: IMcItemTemplate.Vanilla, count: Int): ItemStack {
        val material = Material.matchMaterial(template.material) ?: run {
            log.warn(
                "Unknown Material '{}' for item id={}; falling back to STONE.",
                template.material, template.id,
            )
            Material.STONE
        }
        val stack = ItemStack(material).also { it.amount = count.coerceIn(1, 64) }
        stack.editMeta { meta ->
            template.displayName?.let { rendered ->
                meta.displayName(AsteraTextDeserializer.deserialize(rendered))
            }
            if (template.lore.isNotEmpty()) {
                meta.lore(template.lore.map { rendered -> AsteraTextDeserializer.deserialize(rendered) })
            }
            if (template.customModelData != null) {
                @Suppress("DEPRECATION")
                meta.setCustomModelData(template.customModelData)
            }
            val pdc = meta.persistentDataContainer
            pdc.set(ITEM_ID_KEY, PersistentDataType.STRING, template.id.value)
            for ((rawKey, rawValue) in template.pdcTags) {
                val nk = NamespacedKey.fromString(rawKey) ?: continue
                pdc.set(nk, PersistentDataType.STRING, rawValue)
            }
        }
        return stack
    }

    /** Stamp the canonical `astera:item-id` PDC tag onto a fallback ItemStack. */
    private fun stamp(stack: ItemStack, id: ItemId): ItemStack {
        stack.editMeta { meta ->
            meta.persistentDataContainer.set(ITEM_ID_KEY, PersistentDataType.STRING, id.value)
        }
        return stack
    }

    /** Visible placeholder for "we tried to resolve something and it broke". */
    private fun barrier(): ItemStack = ItemStack(Material.BARRIER)

    private companion object {
        val MM: MiniMessage = MiniMessage.miniMessage()
        val ITEM_ID_KEY: NamespacedKey = NamespacedKey.fromString("astera:item-id")!!
        val log = LoggerFactory.getLogger(PaperItemTemplateRenderer::class.java)
    }
}
