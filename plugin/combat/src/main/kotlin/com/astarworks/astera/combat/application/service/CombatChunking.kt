package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.application.port.outbound.IComputePool
import com.astarworks.astera.core.application.port.outbound.fixedChunks
import com.astarworks.astera.core.application.service.observability.ExecutionCosts

/**
 * Combat's batching policy over the kernel [IComputePool]: small batches run inline on the caller, large ones are cut
 * into fixed-size chunks (never by worker count) so results are identical at any pool size. Workers receive immutable
 * compute inputs only.
 */
public class CombatChunking(
    private val pool: IComputePool,
    private val threshold: Int = System.getProperty("astera.combat.parallelThreshold", "256").toInt(),
    private val chunkSize: Int = System.getProperty("astera.combat.chunkSize", "64").toInt(),
) {
    init { require(threshold > 0 && chunkSize > 0) }

    /** Chunk boundaries and merge order never depend on worker count. */
    public fun <I, O> map(inputs: List<I>, compute: (I) -> O): List<O> {
        if (inputs.size < threshold) return inputs.map(compute)
        ExecutionCosts.increment("combat_parallel_batches")
        val chunks = fixedChunks(inputs.size, chunkSize).map { range ->
            { range.map { compute(inputs[it]) } }
        }
        return pool.forkJoin(chunks).flatten()
    }
}
