package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.ICustomBlockRegistry
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.match.application.config.gate
import com.astarworks.astera.match.application.config.toSpec
import com.astarworks.astera.match.application.port.inbound.IOreNodeAccess
import com.astarworks.astera.match.application.port.outbound.IOreNodeRegistry
import com.astarworks.astera.match.domain.orenode.OreAccess
import com.astarworks.astera.match.domain.orenode.OreNodeAccess

/** [IOreNodeAccess] over the shipped `content/ore-nodes/` files; delegates to [OreNodeAccess], the function enforcement uses. */
public class OreNodeAccessService(
    private val registry: IOreNodeRegistry,
    private val customBlocks: ICustomBlockRegistry,
) : IOreNodeAccess {
    override fun check(stageId: String, block: String, phase: Int?, normaLevel: Int?, tool: ToolType?): OreAccess? {
        val file = registry.find(stageId) ?: return null
        val group = file.nodes.firstOrNull { it.block == block } ?: return null
        val spec = runCatching { group.toSpec(file) { customBlocks.find(BlockType(it)) } }.getOrNull()
            ?: return OreNodeAccess.check(group.gate(file), phase, normaLevel, tool?.tier)
        return OreNodeAccess.check(spec, phase, normaLevel, tool)
    }
}
