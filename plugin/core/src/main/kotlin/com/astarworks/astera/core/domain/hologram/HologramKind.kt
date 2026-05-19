package com.astarworks.astera.core.domain.hologram

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Vendor-neutral payload for a Display-entity-backed hologram (Wave 5h,
 * ADR-0074 §1). Three variants align 1:1 with Bukkit's Display hierarchy:
 *
 * - [Text]  ↔ `org.bukkit.entity.TextDisplay`
 * - [Item]  ↔ `org.bukkit.entity.ItemDisplay`
 * - [Block] ↔ `org.bukkit.entity.BlockDisplay`
 *
 * Sealed so adding a 4th variant (e.g. for an EntityDisplay if Paper ever
 * exposes one) forces every `when` to be updated. The Wave 6b adapter
 * (`PaperHologramGateway`) does the actual Bukkit type conversion at the
 * edge — domain stays Bukkit-free.
 */
public sealed interface HologramKind {

    /**
     * TextDisplay payload. [lines] are [MessageKey] references rather than
     * raw strings — every user-facing text in Astera flows through i18n
     * (`IMessageRenderer`), and a hologram is no exception. Renderer joins
     * lines with `\n` before handing to the adapter.
     *
     * Defaults match Bukkit's TextDisplay defaults except [lineWidth] which
     * Bukkit defaults to 200 explicitly (max characters per wrapped line).
     */
    public data class Text(
        public val lines: List<MessageKey>,
        public val lineWidth: Int = 200,
        public val backgroundColor: HologramColor? = null,
        public val textOpacity: Byte = -1,
        public val shadowed: Boolean = false,
        public val seeThrough: Boolean = false,
        public val defaultBackground: Boolean = false,
        public val alignment: TextAlignment = TextAlignment.CENTER,
    ) : HologramKind {
        init {
            require(lineWidth in 1..1000) {
                "lineWidth must be in 1..1000, got $lineWidth"
            }
        }
    }

    /**
     * ItemDisplay payload. [itemId] resolves through
     * [com.astarworks.astera.core.application.port.outbound.IItemRegistry] at
     * the adapter — the domain never holds an `org.bukkit.inventory.ItemStack`.
     * [displayMode] picks which vanilla item-model perspective renders.
     */
    public data class Item(
        public val itemId: ItemId,
        public val displayMode: ItemDisplayMode = ItemDisplayMode.FIXED,
    ) : HologramKind

    /**
     * BlockDisplay payload. [blockKey] is a Minecraft block-state string
     * the adapter parses via `Bukkit.createBlockData(...)`
     * (e.g. `minecraft:diamond_block` or
     * `minecraft:oak_stairs[facing=north,half=top]`). Parsing happens
     * adapter-side so the domain never imports Bukkit `BlockData`.
     */
    public data class Block(
        public val blockKey: String,
    ) : HologramKind {
        init {
            require(blockKey.isNotBlank()) { "blockKey must not be blank" }
        }
    }
}
