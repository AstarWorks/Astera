package com.astarworks.astera.core.adapter.paper.gui

import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.minimessage.MiniMessage
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
 *   from the `ItemId` constructor / [IItemRegistry.resolveOrNull] and falls
 *   back to a `Material.BARRIER` placeholder so the slot stays visible and
 *   the failure is grep-able in logs.
 *
 * Threading: invoked only from the server main thread (same constraint as
 * the surrounding [PaperGuiAdapter]).
 */
public class PaperItemTemplateRenderer(
    private val items: IItemRegistry<IMcItemTemplate>,
    private val messages: IMessageRenderer,
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
    }

    /**
     * Reactive resolution is the only path that needs defensive guarding:
     * the binding string may not pass [ItemId]'s kebab-case regex (dots,
     * underscores) and `GuiDiffEngine` deliberately doesn't pre-validate.
     */
    private fun resolveReactive(slot: GuiSlot.Reactive, viewer: PlayerId?): ItemStack {
        val itemId = try {
            ItemId(slot.binding.value)
        } catch (_: IllegalArgumentException) {
            log.warn(
                "Reactive binding '{}' is not a valid ItemId; rendering BARRIER placeholder.",
                slot.binding.value,
            )
            return barrier()
        }
        return resolve(itemId, count = 1, viewer = viewer) ?: barrier().also {
            log.warn("Reactive binding '{}' did not resolve to a registered item.", slot.binding.value)
        }
    }

    /**
     * Look up [id] in the item registry and materialize it. Returns `null`
     * when the id is unknown so the caller can decide whether that is a
     * fatal error (BARRIER placeholder) or expected (Reactive miss).
     */
    private fun resolve(id: ItemId, count: Int, viewer: PlayerId?): ItemStack? {
        val template = items.resolveOrNull(id) ?: run {
            log.warn("GUI slot referenced unknown ItemId '{}'.", id.value)
            return null
        }
        return when (template) {
            is IMcItemTemplate.Vanilla -> buildVanilla(template, count, viewer)
            is IMcItemTemplate.External -> {
                log.warn(
                    "External item template '{}' (key={}) — provider adapter not wired; falling back to STONE.",
                    template.id, template.key,
                )
                stamp(ItemStack(Material.STONE).also { it.amount = count.coerceIn(1, 64) }, template.id)
            }
        }
    }

    private fun buildVanilla(template: IMcItemTemplate.Vanilla, count: Int, viewer: PlayerId?): ItemStack {
        val material = Material.matchMaterial(template.material) ?: run {
            log.warn(
                "Unknown Material '{}' for item id={}; falling back to STONE.",
                template.material, template.id,
            )
            Material.STONE
        }
        val stack = ItemStack(material).also { it.amount = count.coerceIn(1, 64) }
        stack.editMeta { meta ->
            template.displayName?.let { key ->
                meta.displayName(MM.deserialize(messages.render(viewer, key)))
            }
            if (template.lore.isNotEmpty()) {
                meta.lore(template.lore.map { key -> MM.deserialize(messages.render(viewer, key)) })
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
