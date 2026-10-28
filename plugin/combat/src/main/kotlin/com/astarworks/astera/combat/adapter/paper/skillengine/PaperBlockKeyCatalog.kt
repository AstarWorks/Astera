package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.application.skillengine.load.IBlockKeyCatalog
import com.astarworks.astera.core.domain.world.BlockType
import org.bukkit.Material

/**
 * Block keys of this server: a vanilla material (`oak_planks`, `minecraft:oak_planks`) or a registered custom block.
 * [customs] is read per lookup: the registry may come up after the skills first load, and a skill that named a custom
 * block is retried once it exists.
 */
internal class PaperBlockKeyCatalog(private val customs: () -> ICustomBlockRegistry?) : IBlockKeyCatalog {
    override fun exists(key: String): Boolean =
        Material.matchMaterial(key) != null ||
            runCatching { customs()?.resolve(BlockType(key)) != null }.getOrDefault(false)
}
