package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IPool
import com.astarworks.astera.core.domain.geometry.MutableVec3

/**
 * Singleton MutableVec3 pool, capacity 256.
 *
 * For projectile per-tick math, particle position calculation, etc.
 * Main-thread only (no synchronization).
 *
 * Usage:
 *   val v = Vec3Pool.default.acquire()
 *   try { ... } finally { Vec3Pool.default.release(v) }
 */
public object Vec3Pool {
    public val default: IPool<MutableVec3> = BoundedArrayPool(
        capacity = 256,
        factory = { MutableVec3() },
        reset = { it.reset() },
    )
}
