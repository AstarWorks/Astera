package com.astarworks.astera.core.adapter.paper.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [MetricsConfig.fromEnv] contract:
 *  - Empty env → disabled, default port.
 *  - Both vars present → both honoured.
 *  - Malformed port → falls back to default (not an exception).
 */
class MetricsConfigTest {

    @Test
    fun `empty env defaults to disabled on port 9092`() {
        val config = MetricsConfig.fromEnv(emptyMap())

        assertThat(config.enabled).isFalse()
        assertThat(config.port).isEqualTo(9092)
        assertThat(MetricsConfig.DEFAULT_PORT).isEqualTo(9092)
    }

    @Test
    fun `explicit env values are honoured`() {
        val config = MetricsConfig.fromEnv(
            mapOf(
                "ASTERA_METRICS_ENABLED" to "true",
                "ASTERA_METRICS_PORT" to "12345",
            ),
        )

        assertThat(config.enabled).isTrue()
        assertThat(config.port).isEqualTo(12345)
    }

    @Test
    fun `malformed port falls back to default`() {
        val config = MetricsConfig.fromEnv(
            mapOf(
                "ASTERA_METRICS_ENABLED" to "true",
                "ASTERA_METRICS_PORT" to "not-a-number",
            ),
        )

        assertThat(config.enabled).isTrue()
        assertThat(config.port).isEqualTo(MetricsConfig.DEFAULT_PORT)
    }

    @Test
    fun `enabled flag is parsed via toBoolean`() {
        // Kotlin's String.toBoolean() returns true only for "true" (case-insensitive).
        val falsy = MetricsConfig.fromEnv(mapOf("ASTERA_METRICS_ENABLED" to "yes"))
        val truthy = MetricsConfig.fromEnv(mapOf("ASTERA_METRICS_ENABLED" to "TRUE"))

        assertThat(falsy.enabled).isFalse()
        assertThat(truthy.enabled).isTrue()
    }

    @Test
    fun `port without enabled keeps default-disabled posture`() {
        val config = MetricsConfig.fromEnv(mapOf("ASTERA_METRICS_PORT" to "9999"))

        assertThat(config.enabled).isFalse()
        assertThat(config.port).isEqualTo(9999)
    }
}
