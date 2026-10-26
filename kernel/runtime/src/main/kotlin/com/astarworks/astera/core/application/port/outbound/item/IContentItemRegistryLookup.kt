package com.astarworks.astera.core.application.port.outbound.item

/**
 * Narrow read-only lookup that resolves a content-pack item id (the string
 * shape persisted in [com.astarworks.astera.core.domain.player.InventoryItemSlot.ContentItem.itemId])
 * to the **vendor-neutral material key** of its base ItemStack
 * (Wave 6c §C, ADR-0160).
 *
 * # Why this exists (and why it is *not* `IItemRegistry`)
 *
 * Wave 6b-3 ([ADR-0158]) shipped [com.astarworks.astera.core.adapter.paper
 * .inventory.PaperInventorySnapshotSerializer], the Bukkit codec for
 * [com.astarworks.astera.core.domain.player.PlayerInventorySnapshot]. Its
 * `decodeSlot` branch for [com.astarworks.astera.core.domain.player
 * .InventoryItemSlot.ContentItem] could only `WARN+null` because no Core-level
 * surface mapped a content-pack id back to a Bukkit `Material`.
 *
 * The full [com.astarworks.astera.core.application.port.outbound.IItemRegistry]
 * surface is too heavy for the restore path: it returns
 * [com.astarworks.astera.core.domain.item.ItemSpec] (template + skills +
 * attributes + i18n + PDC tags) when all the Paper serializer needs is the
 * base material to spawn the right vanilla `ItemStack`. Hot-path restoration
 * pulling the entire spec on every slot is unnecessary work.
 *
 * # Why `materialKey: String?` instead of `Material`
 *
 * `org.bukkit.Material` cannot be exposed from
 * `core.application.port.outbound.*` per the ADR-0002 vendor-neutral
 * boundary (`ArchitectureTest.kt` rule `core.adapter.api.* must not import
 * Bukkit family` + the broader `*.application.* must not import Bukkit`
 * rule). The lookup therefore returns the **Vanilla material key string**
 * (e.g. `"minecraft:diamond_sword"`); the Paper adapter resolves it to a
 * `Material` via `Material.matchMaterial(...)` at the boundary.
 *
 * # Contract
 *
 * - Every operation returns `null` on miss (unknown id, malformed string,
 *   External template id). `null` is **expected**, not an error — the caller
 *   handles it by leaving the slot empty (see
 *   [com.astarworks.astera.core.adapter.paper.inventory
 *   .PaperInventorySnapshotSerializer]). No exceptions.
 * - Implementations must be safe to call from any thread (Phase 1 PoC
 *   restore runs on the Bukkit main thread, but cross-plugin callers may
 *   resolve off-main as the registry surface is read-only).
 *
 * # Input format
 *
 * [rawItemId] is the string stored in
 * [com.astarworks.astera.core.domain.player.InventoryItemSlot.ContentItem.itemId]
 * — by convention namespaced as `"astera:<kebab-id>"` (the prefix is the
 * Adventure-style content-pack namespace), though the impl tolerates the
 * bare form `"<kebab-id>"` for forward compatibility. Anything else
 * (`upper-case`, `spaces`, `multiple colons`, unknown namespace) resolves
 * to `null` without logging — silent miss is the established
 * `ItemId` validation posture.
 *
 * # External templates
 *
 * [com.astarworks.astera.core.domain.item.ItemTemplate.External] entries
 * (Oraxen / ItemAdder / MythicItems) resolve to `null` for material keys —
 * external providers own the material at give-time. A future port may
 * expose an `externalKey` accessor; for now the restore path treats
 * external items the same as unknown ids (slot stays empty).
 */
public interface IContentItemRegistryLookup {

    /**
     * Resolve [rawItemId] to its Vanilla material key (e.g.
     * `"minecraft:diamond_sword"`), or `null` if the id is unknown,
     * malformed, or backed by an
     * [com.astarworks.astera.core.domain.item.ItemTemplate.External]
     * template.
     */
    public fun bukkitMaterialKeyFor(rawItemId: String): String?
}
