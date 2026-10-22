package com.astarworks.astera.persistence

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class PersistenceBootstrapTest {
    @Test
    fun `migration bootstrap failure closes the pool before propagating`() {
        // No JDBC URL: Flyway cannot acquire a connection. No database or Docker is needed.
        val pool = HikariDataSource()
        try {
            assertThatThrownBy { migrateAndConnect(pool, LoggerFactory.getLogger(javaClass)) }
                .isInstanceOf(Exception::class.java)
            assertThat(pool.isClosed).isTrue()
        } finally {
            pool.close()
        }
    }
}
