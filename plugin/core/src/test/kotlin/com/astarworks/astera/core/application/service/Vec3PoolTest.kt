package com.astarworks.astera.core.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Vec3PoolTest {

    @Test
    fun `Vec3Pool default acquires and releases MutableVec3`() {
        val pool = Vec3Pool.default
        val v = pool.acquire()
        pool.release(v)
        // no exception = pass; pool size must be 1 after release
        assertThat(pool.size()).isEqualTo(1)
    }

    @Test
    fun `acquired instance is properly reset after release and reacquire cycle`() {
        val pool = Vec3Pool.default

        val v = pool.acquire()
        v.set(99.0, 88.0, 77.0)
        pool.release(v)

        val reacquired = pool.acquire()
        assertThat(reacquired.x).isEqualTo(0.0)
        assertThat(reacquired.y).isEqualTo(0.0)
        assertThat(reacquired.z).isEqualTo(0.0)
    }

    @Test
    fun `capacity is 256`() {
        assertThat(Vec3Pool.default.capacity).isEqualTo(256)
    }
}
