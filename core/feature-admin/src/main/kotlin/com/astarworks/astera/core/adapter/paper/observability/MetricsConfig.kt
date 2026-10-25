package com.astarworks.astera.core.adapter.paper.observability

/**
 * Env-var configuration DTO for the Prometheus scrape endpoint.
 *
 * Wave _ops (Phase 1.5): kept as a tiny DTO with companion factory rather
 * than YAML so the metrics endpoint is wired entirely from container env —
 * no plugin restart required to roll out infra changes. Default is **disabled**
 * so local Paper runs don't bind a port unexpectedly.
 *
 * Env vars:
 *  - `ASTERA_METRICS_ENABLED` (boolean, default `false`)
 *  - `ASTERA_METRICS_PORT` (int, default [DEFAULT_PORT])
 */
public data class MetricsConfig(
    val port: Int,
    val enabled: Boolean,
) {
    public companion object {
        public const val DEFAULT_PORT: Int = 9092

        public fun fromEnv(env: Map<String, String> = System.getenv()): MetricsConfig {
            val enabled = env["ASTERA_METRICS_ENABLED"]?.toBoolean() ?: false
            val port = env["ASTERA_METRICS_PORT"]?.toIntOrNull() ?: DEFAULT_PORT
            return MetricsConfig(port = port, enabled = enabled)
        }
    }
}
