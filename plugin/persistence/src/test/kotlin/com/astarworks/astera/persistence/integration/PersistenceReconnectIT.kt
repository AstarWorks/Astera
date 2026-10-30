package com.astarworks.astera.persistence.integration

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.persistence.PostgresConfig
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import com.astarworks.astera.persistence.platform.PersistenceRuntime
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * ADR-0311 P3: `rewire persistence --reconnect` swaps the pool behind the shared data source against a real Postgres.
 * The old pool is drained and closed off the calling thread; the new one serves queries.
 */
class PersistenceReconnectIT : PostgresIntegrationTestBase() {

    @Test
    fun `reconnect swaps the pool, closes the old one asynchronously and keeps serving queries`() {
        val ds = context.dataSource
        val config = PostgresConfig(ds.jdbcUrl, ds.username, ds.password)
        val runtime = PersistenceRuntime(LoggerFactory.getLogger(javaClass), configSource = { config }, drainTimeoutMs = 2_000L)
        try {
            assertThat(runtime.connect()).isEqualTo(Result.Success(true))
            val first = checkNotNull(runtime.currentPool())
            assertThat(runtime.attributeStore).isNotNull()

            assertThat(runtime.reconnect()).isInstanceOf(Result.Success::class.java)
            val second = checkNotNull(runtime.currentPool())
            assertThat(second).isNotSameAs(first)
            assertThat(runtime.awaitDrained(5_000L)).isTrue()
            assertThat(first.isClosed).isTrue()
            assertThat(second.isClosed).isFalse()
            second.connection.use { c -> c.createStatement().use { it.executeQuery("select 1").use { rs -> assertThat(rs.next()).isTrue() } } }

            // A repeat reconnect still works and the carried repositories stay the same instance.
            val store = runtime.attributeStore
            assertThat(runtime.reconnect()).isInstanceOf(Result.Success::class.java)
            assertThat(runtime.attributeStore).isSameAs(store)
            assertThat(runtime.awaitDrained(5_000L)).isTrue()
            assertThat(second.isClosed).isTrue()
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `a reconnect against an unreachable database fails and leaves the old pool serving`() {
        val ds = context.dataSource
        var config = PostgresConfig(ds.jdbcUrl, ds.username, ds.password)
        val runtime = PersistenceRuntime(LoggerFactory.getLogger(javaClass), configSource = { config })
        try {
            runtime.connect()
            val pool = checkNotNull(runtime.currentPool())
            config = PostgresConfig("jdbc:postgresql://127.0.0.1:1/none", "x", "x")
            assertThat(runtime.reconnect()).isInstanceOf(Result.Failure::class.java)
            assertThat(runtime.currentPool()).isSameAs(pool)
            assertThat(pool.isClosed).isFalse()
        } finally {
            runtime.close()
        }
    }
}
