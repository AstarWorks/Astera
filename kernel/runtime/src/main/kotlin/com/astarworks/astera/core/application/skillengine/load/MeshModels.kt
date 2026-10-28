package com.astarworks.astera.core.application.skillengine.load

/** What a `mesh` op draws (SKL-12). */
public sealed interface MeshModel {
    /** A vanilla material (`item: minecraft:end_rod`): no pack model involved. */
    public data class Vanilla(val material: String) : MeshModel

    /** A pack item definition (`astera:<id>`), which must exist in the resource pack. */
    public data class Pack(val key: String) : MeshModel

    /** The caster's main-hand stack, copied at spawn (`item: main_hand`). */
    public data object HeldItem : MeshModel
}

/**
 * Single resolver of a mesh op's `item` + `part` to what is drawn, shared by the Paper gateway and the pack-reference lint
 * so the two cannot disagree. A pack model comes from the item's point table (`content/frames/<item>.yaml`,
 * `parts.<part>.model`); an item without a table is `astera:<item>` (its generated icon model).
 */
public object MeshModels {
    public const val MAIN_HAND: String = "main_hand"
    private const val NAMESPACE: String = "astera"

    public fun resolve(item: String, part: String?, tables: Map<String, ItemFrames>): MeshModel {
        if (item == MAIN_HAND) return MeshModel.HeldItem
        if (item.startsWith("minecraft:")) return MeshModel.Vanilla(item.removePrefix("minecraft:"))
        val declared = tables[item]?.partFor(part)?.model
        if (declared != null) return MeshModel.Pack(if (':' in declared) declared else "$NAMESPACE:$declared")
        return MeshModel.Pack(if (part == null) "$NAMESPACE:$item" else "$NAMESPACE:$item-${part.replace('_', '-')}")
    }
}
