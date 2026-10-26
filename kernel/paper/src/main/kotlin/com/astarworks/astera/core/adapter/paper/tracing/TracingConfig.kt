package com.astarworks.astera.core.adapter.paper.tracing

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import org.slf4j.LoggerFactory

/**
 * Wave 5u (ADR-0087): env-var configuration for distributed tracing.
 *
 * Default backend is the in-process logging exporter so spans are visible in
 * the server log without infra. Operators opt into OTLP/Jaeger by setting
 * `ASTERA_TRACING_BACKEND=otlp` plus `ASTERA_TRACING_OTLP_ENDPOINT`.
 *
 * Env vars:
 *  - `ASTERA_TRACING_ENABLED` (boolean, default `false`) — master switch.
 *    When false, [buildOpenTelemetry] returns [OpenTelemetry.noop] which
 *    collapses every propagator call to a no-op span (silly-knuth §5).
 *  - `ASTERA_TRACING_BACKEND` (`console` | `otlp`, default `console`)
 *  - `ASTERA_TRACING_OTLP_ENDPOINT` (URL, default `http://localhost:4317`)
 *  - `ASTERA_TRACING_SERVICE_NAME` (string, default `astera`)
 */
public data class TracingConfig(
    public val enabled: Boolean,
    public val backend: Backend,
    public val otlpEndpoint: String,
    public val serviceName: String,
) {
    public enum class Backend { CONSOLE, OTLP }

    public companion object {
        public const val DEFAULT_OTLP_ENDPOINT: String = "http://localhost:4317"
        public const val DEFAULT_SERVICE_NAME: String = "astera"

        public fun fromEnv(env: Map<String, String> = System.getenv()): TracingConfig {
            val enabled = env["ASTERA_TRACING_ENABLED"]?.toBoolean() ?: false
            val backend = when (env["ASTERA_TRACING_BACKEND"]?.lowercase()) {
                "otlp" -> Backend.OTLP
                else -> Backend.CONSOLE
            }
            return TracingConfig(
                enabled = enabled,
                backend = backend,
                otlpEndpoint = env["ASTERA_TRACING_OTLP_ENDPOINT"] ?: DEFAULT_OTLP_ENDPOINT,
                serviceName = env["ASTERA_TRACING_SERVICE_NAME"] ?: DEFAULT_SERVICE_NAME,
            )
        }
    }
}

/**
 * Builds the [OpenTelemetry] instance to inject into
 * [OpenTelemetryTraceContextPropagator]. Returns [OpenTelemetry.noop] when
 * tracing is disabled so the rest of Astera holds an active reference but
 * pays zero cost.
 */
public object TracingBootstrap {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun buildOpenTelemetry(config: TracingConfig): OpenTelemetry {
        if (!config.enabled) {
            log.info("Tracing disabled (set ASTERA_TRACING_ENABLED=true to enable).")
            return OpenTelemetry.noop()
        }

        val resource = Resource.getDefault().merge(
            Resource.create(
                Attributes.of(AttributeKey.stringKey("service.name"), config.serviceName),
            ),
        )

        val tracerProviderBuilder = SdkTracerProvider.builder().setResource(resource)

        when (config.backend) {
            TracingConfig.Backend.CONSOLE -> {
                tracerProviderBuilder.addSpanProcessor(
                    SimpleSpanProcessor.create(LoggingSpanExporter.create()),
                )
                log.info("Tracing backend: CONSOLE (LoggingSpanExporter).")
            }
            TracingConfig.Backend.OTLP -> {
                val exporter = OtlpGrpcSpanExporter.builder()
                    .setEndpoint(config.otlpEndpoint)
                    .build()
                tracerProviderBuilder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                log.info("Tracing backend: OTLP at {}.", config.otlpEndpoint)
            }
        }

        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProviderBuilder.build())
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build()
    }
}
