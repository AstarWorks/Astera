package com.astarworks.astera.core.domain.entity

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Visual archetype of a display-entity hologram (1.19.4+ Display family).
 *
 * Holograms in Astera are display entities controlled by the
 * [com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner]
 * with a [com.astarworks.astera.core.domain.entity.SpawnSpec.DisplayNpc] spec.
 * The kind enumerates the three concrete Display entity variants Paper exposes
 * (text / item / block) in a vendor-neutral form.
 *
 * Wave 5l owns the domain shape only; Paper-side translation to
 * `TextDisplay` / `ItemDisplay` / `BlockDisplay` lives in Wave 6b.
 */
public sealed interface HologramKind {

    /** Floating text label. */
    public data class Text(public val messageKey: MessageKey) : HologramKind

    /** Floating item icon (e.g. shop preview). */
    public data class Item(public val itemId: ItemId) : HologramKind

    /**
     * Floating block model. The [blockKey] is a vendor-neutral material key
     * (e.g. `minecraft:oak_planks`). Adapter layer resolves to Bukkit's
     * `Material`.
     */
    public data class Block(public val blockKey: String) : HologramKind {
        init {
            require(blockKey.isNotBlank()) { "HologramKind.Block.blockKey must not be blank" }
        }
    }
}

/**
 * Opaque reference to a previously-spawned hologram (returned by a display-NPC
 * spawn) — used by [NpcTraitSpec.HologramAttachment] to glue a label onto a
 * separately-spawned NPC.
 */
public data class HologramHandle(
    public val entity: EntityHandle,
)
