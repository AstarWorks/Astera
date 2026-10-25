package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IPool

/**
 * Bounded array-backed pool. Main-thread only (no synchronization).
 *
 * @param capacity max retained instances after release
 * @param factory create a fresh instance when pool is empty
 * @param reset mutate the instance back to a clean state before reuse
 */
public class BoundedArrayPool<T : Any>(
    public override val capacity: Int,
    private val factory: () -> T,
    private val reset: (T) -> Unit = {},
) : IPool<T> {

    init {
        require(capacity >= 1) { "BoundedArrayPool capacity must be >= 1: $capacity" }
    }

    private val pool: ArrayDeque<T> = ArrayDeque(capacity)

    public override fun acquire(): T = pool.removeLastOrNull() ?: factory()

    public override fun release(t: T) {
        if (pool.size < capacity) {
            reset(t)
            pool.addLast(t)
        }
        // else: pool full, drop on the floor (GC reclaims t)
    }

    public override fun size(): Int = pool.size
}
