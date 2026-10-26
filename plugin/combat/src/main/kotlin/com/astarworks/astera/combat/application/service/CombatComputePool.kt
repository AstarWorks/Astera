package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.application.service.observability.ExecutionCosts
import java.util.concurrent.ForkJoinPool

/** Internal until T4b's IComputePool is available. Workers receive immutable compute inputs only. */
public class CombatComputePool(
    private val threshold: Int = System.getProperty("astera.combat.parallelThreshold", "256").toInt(),
    private val chunkSize: Int = System.getProperty("astera.combat.chunkSize", "64").toInt(),
    parallelism: Int = Runtime.getRuntime().availableProcessors().coerceAtMost(4),
) : AutoCloseable {
    private val pool = if (parallelism > 0) ForkJoinPool(parallelism) else null
    init { require(threshold > 0 && chunkSize > 0) }

    /** Chunk boundaries and merge order never depend on worker count. parallelism=0 is the sim path. */
    public fun <I, O> map(inputs: List<I>, compute: (I) -> O): List<O> {
        if (pool == null || inputs.size < threshold) return inputs.map(compute)
        ExecutionCosts.increment("combat_parallel_batches")
        val tasks = inputs.chunked(chunkSize).map { chunk -> pool.submit<List<O>> { chunk.map(compute) } }
        // Join every task, even on failure: no work from this phase may escape into the next tick.
        var failure: Exception? = null
        val output = ArrayList<O>(inputs.size)
        for (task in tasks) {
            try { output.addAll(task.join()) } catch (ex: Exception) { if (failure == null) failure = ex }
        }
        failure?.let { throw it }
        return output
    }

    override fun close() { pool?.shutdown() }
}
