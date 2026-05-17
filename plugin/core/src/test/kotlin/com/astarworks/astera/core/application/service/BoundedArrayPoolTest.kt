package com.astarworks.astera.core.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BoundedArrayPoolTest {

    @Test
    fun `acquire from empty pool returns fresh instance via factory`() {
        var factoryCount = 0
        val pool = BoundedArrayPool<String>(capacity = 4, factory = { factoryCount++; "item" })

        pool.acquire()
        pool.acquire()

        assertThat(factoryCount).isEqualTo(2)
    }

    @Test
    fun `release adds to pool then subsequent acquire returns same instance`() {
        val pool = BoundedArrayPool<StringBuilder>(capacity = 4, factory = { StringBuilder() })

        val instance = pool.acquire()
        pool.release(instance)
        val reacquired = pool.acquire()

        assertThat(reacquired).isSameAs(instance)
    }

    @Test
    fun `release over capacity drops the instance`() {
        val pool = BoundedArrayPool<String>(capacity = 2, factory = { "item" })

        repeat(5) { pool.release("dropped") }

        assertThat(pool.size()).isEqualTo(2)
    }

    @Test
    fun `reset is called on release`() {
        var resetCount = 0
        val pool = BoundedArrayPool<String>(
            capacity = 4,
            factory = { "item" },
            reset = { resetCount++ },
        )

        val a = pool.acquire()
        val b = pool.acquire()
        pool.release(a)
        pool.release(b)

        assertThat(resetCount).isEqualTo(2)
    }

    @Test
    fun `capacity 0 throws on construction`() {
        assertThrows<IllegalArgumentException> {
            BoundedArrayPool<String>(capacity = 0, factory = { "x" })
        }
    }

    @Test
    fun `acquire from pool does NOT call factory again`() {
        var factoryCount = 0
        val pool = BoundedArrayPool<String>(capacity = 4, factory = { factoryCount++; "item" })

        // prime the pool: acquire once (factory called), release back
        val instance = pool.acquire()
        assertThat(factoryCount).isEqualTo(1)
        pool.release(instance)

        // acquire again — should come from pool, not factory
        pool.acquire()
        assertThat(factoryCount).isEqualTo(1)
    }
}
