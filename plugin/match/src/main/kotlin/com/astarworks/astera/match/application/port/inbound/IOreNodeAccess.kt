package com.astarworks.astera.match.application.port.inbound

import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.match.domain.orenode.OreAccess

/**
 * Read-only ore-node gate query (ADR-0319): can a miner at [phase] / [normaLevel] with [tool] damage the nodes of block [block]
 * in stage template [stageId] (`content/ore-nodes/<stageId>.yaml`)? The verdict is [com.astarworks.astera.match.domain.orenode.OreNodeAccess],
 * the same function `OreNodeService` enforces with. Registered in the `ServiceRegistry` by the match plugin. A caller without a
 * registry (the standalone bot runner) calls `OreNodeAccess.check(group.gate(file), ...)` on the loaded file instead; same function.
 */
public interface IOreNodeAccess {
    /** Null when the stage has no ore-node file or no node of [block]. [phase] / [normaLevel] null = that gate does not apply; [tool] null = bare hand. */
    public fun check(stageId: String, block: String, phase: Int?, normaLevel: Int?, tool: ToolType?): OreAccess?
}
