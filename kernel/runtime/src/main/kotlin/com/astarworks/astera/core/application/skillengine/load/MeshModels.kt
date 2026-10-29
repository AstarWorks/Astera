package com.astarworks.astera.core.application.skillengine.load

/** What a `mesh` op draws (SKL-12). */
public sealed interface MeshModel {
    /** A vanilla material (`item: minecraft:end_rod`); [itemModel] is the item_model component stacked on it (`part: astera:<id>`, v1 item element), null for none. */
    public data class Vanilla(val material: String, val itemModel: String? = null) : MeshModel

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

    public fun resolve(rawItem: String, part: String?, tables: Map<String, ItemFrames>): MeshModel {
        if (rawItem == MAIN_HAND) return MeshModel.HeldItem
        if (rawItem.startsWith("minecraft:")) return MeshModel.Vanilla(rawItem.removePrefix("minecraft:"), part)
        // a pack item may be spelled with its namespace (`astera:couture-rapier-blade-a`); the namespace is added exactly once below
        val item = rawItem.removePrefix("$NAMESPACE:")
        require(':' !in item) { "mesh item '$rawItem': only the minecraft: and $NAMESPACE: namespaces exist" }
        val declared = tables[item]?.partFor(part)?.model
        if (declared != null) return MeshModel.Pack(if (':' in declared) declared else "$NAMESPACE:$declared")
        return MeshModel.Pack(if (part == null) "$NAMESPACE:$item" else "$NAMESPACE:$item-${part.replace('_', '-')}")
    }
}
