package com.astarworks.astera.core.adapter.paper.gui

import com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.ResolvedReactiveSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
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
 * One item path (ADR-0241): the base stack of every slot comes from
 * [PaperItemMaterializer.buildFor], the same function that builds the item a player is given, so
 * material, `item_model` art, name, lore, glint, flags and components are identical. This class only
 * appends GUI extras (price / stock / click-hint lore, reactive overrides, player-head skins).
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
    /** Resolved lazily: the Items plugin registers the materializer after Core enabled. */
    private val materializer: () -> PaperItemMaterializer?,
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
        is GuiSlot.Static -> resolve(slot.itemId, slot.count, viewer)?.let { applyStaticExtras(it, slot, viewer) }
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
     * (`player.coins.gold`, `player.kills`).
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
     * Admin-panel style dynamic rows: override the display name, append lore
     * lines and set a player-head skin from the [GuiSlot.Static] extras.
     */
    private fun applyStaticExtras(stack: ItemStack, slot: GuiSlot.Static, viewer: PlayerId?): ItemStack {
        if (slot.displayName == null && slot.lore.isEmpty() && slot.headOf == null) return stack
        stack.editMeta { meta ->
            slot.displayName?.let { t ->
                meta.displayName(AsteraTextDeserializer.deserialize(messages.render(viewer, t.key, t.args)))
            }
            if (slot.lore.isNotEmpty()) {
                val existing = meta.lore() ?: emptyList()
                meta.lore(
                    existing + slot.lore.map { t ->
                        AsteraTextDeserializer.deserialize(messages.render(viewer, t.key, t.args))
                    },
                )
            }
            val head = slot.headOf
            if (head != null && meta is org.bukkit.inventory.meta.SkullMeta) {
                meta.owningPlayer = org.bukkit.Bukkit.getOfflinePlayer(head.uuid)
            }
        }
        return stack
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
     * Look up [id] in the item registry and build the real item stack for it. Returns `null`
     * when the id is unknown so the caller can decide whether that is a
     * fatal error (BARRIER placeholder) or expected (Reactive miss).
     */
    private fun resolve(id: ItemId, count: Int, viewer: PlayerId?): ItemStack? {
        val spec = items.find(id) ?: run {
            log.warn("GUI slot referenced unknown ItemId '{}'.", id.value)
            return null
        }
        val builder = materializer() ?: run {
            log.warn("No item materializer registered (Items plugin not loaded); cannot render '{}'.", id.value)
            return null
        }
        return builder.buildFor(spec, viewer, count)
    }

    /** Visible placeholder for "we tried to resolve something and it broke". */
    private fun barrier(): ItemStack = ItemStack(Material.BARRIER)

    private companion object {
        val log = LoggerFactory.getLogger(PaperItemTemplateRenderer::class.java)
    }
}
