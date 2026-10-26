package com.astarworks.astera.core.application.port.outbound

import java.util.UUID
import java.util.concurrent.CompletableFuture

public enum class WorkClass { Combat, Background }

/** Pure CPU work only. Chunks are fixed by input indices, never by worker count. */
public interface IComputePool {
    public fun <T> forkJoin(chunks: List<() -> T>): List<T>
    public fun <T> async(cls: WorkClass, block: () -> T): CompletableFuture<T>
}

/** Stable cast seed; callers keep the monotonic cast counter with the caster's state. */
public fun castSeed(scopeSeed: Long, casterUuid: UUID, castCounter: Long): Long {
    fun mix(value: Long): Long {
        var x = value
        x = (x xor (x ushr 30)) * -4658895280553007687L
        x = (x xor (x ushr 27)) * -7723592293110705685L
        return x xor (x ushr 31)
    }
    return mix(mix(mix(scopeSeed) xor casterUuid.mostSignificantBits) xor casterUuid.leastSignificantBits xor mix(castCounter))
}

/** Invariant across pool sizes, including the inline simulator. */
public fun fixedChunks(size: Int, chunkSize: Int): List<IntRange> {
    require(size >= 0 && chunkSize > 0)
    return (0 until size step chunkSize).map { start -> start until minOf(size.toLong(), start.toLong() + chunkSize).toInt() }
}
