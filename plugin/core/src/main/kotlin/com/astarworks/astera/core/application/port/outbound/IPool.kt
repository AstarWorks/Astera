package com.astarworks.astera.core.application.port.outbound

/**
 * Outbound port: object pool for hot-path allocations.
 *
 * Use when:
 *  - object is created and destroyed at >1000/sec
 *  - construction cost > reset cost
 *  - lifetime is bounded (acquire → use → release within a known scope)
 *
 * Pools are intentionally NOT thread-safe; main-thread only by convention
 * (per ADR-0019 / ADR-0022). For off-thread allocation, construct fresh.
 */
public interface IPool<T : Any> {
    /** Take an instance from the pool, or construct a fresh one if empty. */
    public fun acquire(): T

    /** Return an instance to the pool. If pool is at capacity, the instance is dropped (GC handles it). */
    public fun release(t: T)

    /** Current size for telemetry / debugging. */
    public fun size(): Int

    /** Max retained instances. Releases beyond this drop the instance. */
    public val capacity: Int
}
