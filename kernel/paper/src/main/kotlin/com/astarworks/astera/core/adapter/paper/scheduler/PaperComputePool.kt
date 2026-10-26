package com.astarworks.astera.core.adapter.paper.scheduler

import com.astarworks.astera.core.application.port.outbound.IComputePool
import com.astarworks.astera.core.application.port.outbound.WorkClass
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit

/** Bounded worker count, including compensation workers. No Bukkit access from compute callbacks. */
public class PaperComputePool(parallelism: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)) : IComputePool, AutoCloseable {
    private val pool = ForkJoinPool(
        parallelism, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, false,
        parallelism, parallelism, 1, { true }, 60L, TimeUnit.SECONDS,
    )

    override fun <T> forkJoin(chunks: List<() -> T>): List<T> {
        val tasks = chunks.map { chunk -> pool.submit<T> { chunk() } }
        return tasks.map { it.join() }
    }

    override fun <T> async(cls: WorkClass, block: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(block, pool)

    override fun close() { pool.shutdownNow() }
}
