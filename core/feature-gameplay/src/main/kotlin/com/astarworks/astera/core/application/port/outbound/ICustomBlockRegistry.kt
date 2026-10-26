package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.application.contents.ReloadableContents
import com.astarworks.astera.core.domain.block.CustomBlockSpec
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.BlockType

/**
 * Outbound port: read-side view onto the custom-block registry (Wave 5x,
 * ADR-0090).
 *
 * Loaded from `content/blocks/<id>.yaml` via [CustomBlockContents] (the
 * production impl) — `find / all / resolve` are the substrate's `Contents`
 * surface; the registry adds the [resolveByItem] inverse for the place
 * handler (Item → CustomBlockSpec when the held item has
 * `ItemSpec.placeableAs` set).
 *
 * Hot-reload semantics: implementations inherit `IReloadableContents` so
 * `/astera reload blocks` (Wave _infra-2b ADR-0065) atomically swaps the
 * registry without dropping `applyDamageFromSkill` in-flight calls.
 */
public interface ICustomBlockRegistry : Contents<BlockType, CustomBlockSpec>, ReloadableContents {

    /** Equivalent to [Contents.find] with a clearer name at the gateway call site. */
    public fun resolve(id: BlockType): CustomBlockSpec? = find(id)

    /**
     * Inverse lookup: given the placement [itemId], return the
     * [CustomBlockSpec] whose `placedFrom == itemId`, or null if none of the
     * registered specs claim this item. Implementations typically build a
     * `Map<ItemId, CustomBlockSpec>` from the canonical map on each
     * [Contents.all] snapshot — cheap because the registry size stays small
     * (tens to low hundreds of custom blocks).
     */
    public fun resolveByItem(itemId: ItemId): CustomBlockSpec?
}
