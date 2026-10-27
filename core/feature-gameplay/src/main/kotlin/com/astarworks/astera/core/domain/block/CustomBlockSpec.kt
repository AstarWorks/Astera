package com.astarworks.astera.core.domain.block

import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.BlockMaterial
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolType

/**
 * Authored definition of a custom Astera block (Wave 5x, ADR-0090).
 *
 * Replaces the pre-Wave-5x ad-hoc hardcoding of `astera:core_block` /
 * `astera:barrier_block` with a YAML-driven registry: drop a file in
 * `content/blocks/<id>.yaml` and the block becomes placeable, durable, and
 * lootable end-to-end.
 *
 * # Fields
 *
 * - [id] — namespaced [BlockType] (`astera:hardened_soil`).
 * - [displayName] — inline en/ja `LocalizedText`; renderer resolves at
 *   query time.
 * - [baseBukkitMaterial] — original material for sound and non-visual definitions.
 * - [visual] — complete reserved world state (ADR-0299); visual-backed cover never
 *   identifies ordinary terrain by its base material.
 * - [material] — coarse [BlockMaterial] used as the right axis of the
 *   damage matrix when [toolEffectiveness] doesn't cover a cell.
 * - [baseDurability] — initial + max HP of the block. Custom blocks may set
 *   this far above the vanilla 100 default (e.g. 5000 for obsidian-grade
 *   hardness).
 * - [toolEffectiveness] — optional per-block matrix override. Keyed by
 *   `ToolType.serialKey()` ("PICKAXE/IRON", "NONE", …). The gateway
 *   consults this first; on miss it falls through to the global
 *   `BlockDurabilityRule`.
 * - [drops] — what the block yields when broken, with per-drop tool gates
 *   + chance rolls (see [BlockDrop]).
 * - [placedFrom] — optional [ItemId] of the placement item. The gateway
 *   uses this with `ItemSpec.placeableAs` to short-circuit the inverse
 *   lookup ("what custom block does this item place?").
 * - [immuneTo] — set of [BlockMaterial]s the block is fully immune to.
 *   A skill profile whose `effectiveTool.material` lookup lands in this
 *   set produces [IneffectiveReason.ImmuneByMaterial].
 * - [texture] — namespaced block surface for the pack-generated cube model.
 * - [onRightClick] — optional [GuiIntent] dispatched when a player
 *   right-clicks the placed block (Wave 5block-embed, ADR-0120 extension
 *   of ADR-0090). When non-null, the runtime registers the intent in
 *   the GUI intent router so plugins that own the intent prefix can
 *   handle it; combined with [com.astarworks.astera.core.application.port.outbound.block.IBlockInteractionRouter]
 *   for position-specific bindings to give content authors a low-code
 *   way to wire "right-click this block opens this GUI / runs this
 *   action" without writing Kotlin. `null` (default) means the block
 *   has no right-click behaviour and the routing layer skips it
 *   completely.
 */
public data class CustomBlockSpec(
    val id: BlockType,
    val displayName: LocalizedText,
    val baseBukkitMaterial: String,
    val material: BlockMaterial,
    val baseDurability: Int,
    val toolEffectiveness: Map<ToolType, Int>? = null,
    val drops: List<BlockDrop> = emptyList(),
    val placedFrom: ItemId? = null,
    val immuneTo: Set<BlockMaterial> = emptySet(),
    val texture: String? = null,
    val onRightClick: GuiIntent? = null,
    val visual: BlockVisual? = null,
) {
    init {
        require(baseDurability >= 1) {
            "CustomBlockSpec.baseDurability must be >= 1, got $baseDurability"
        }
        require(baseBukkitMaterial.isNotBlank()) {
            "CustomBlockSpec.baseBukkitMaterial must not be blank"
        }
    }
}
