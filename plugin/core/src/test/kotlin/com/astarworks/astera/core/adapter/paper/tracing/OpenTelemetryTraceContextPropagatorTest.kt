package com.astarworks.astera.core.adapter.paper.tracing

import com.astarworks.astera.core.application.port.outbound.tracing.ITraceContextPropagator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [OpenTelemetryTraceContextPropagator] contract (Wave 5u, ADR-0087).
 *
 * Uses the in-process SDK wired through [TracingBootstrap] with the CONSOLE
 * exporter so we don't need a Jaeger collector running.
 */
class OpenTelemetryTraceContextPropagatorTest {

    @Test
    fun `withSpan installs context for the duration of block`() {
        val otel = TracingBootstrap.buildOpenTelemetry(
            TracingConfig(
                enabled = true,
                backend = TracingConfig.Backend.CONSOLE,
                otlpEndpoint = "http://unused",
                serviceName = "astera-test",
            ),
        )
        val propagator = OpenTelemetryTraceContextPropagator(otel)

        assertThat(propagator.currentSpan()).isNull()

        val captured = propagator.withSpan("test.span", mapOf("k" to "v")) {
            propagator.currentSpan()
        }

        assertThat(captured).isNotNull
        assertThat(captured!!.traceId).hasSize(32)  // W3C 16-byte trace id, hex = 32 chars
        assertThat(captured.spanId).hasSize(16)     // W3C 8-byte span id
    }

    @Test
    fun `injectIntoMessage adds Astera trace id header inside span`() {
        val otel = TracingBootstrap.buildOpenTelemetry(
            TracingConfig(
                enabled = true,
                backend = TracingConfig.Backend.CONSOLE,
                otlpEndpoint = "http://unused",
                serviceName = "astera-test",
            ),
        )
        val propagator = OpenTelemetryTraceContextPropagator(otel)

        val headers: MutableMap<String, String> = mutableMapOf()
        propagator.withSpan("proxy.send") {
            propagator.injectIntoMessage("astera:reward-credit", headers)
        }

        assertThat(headers).containsKey(ITraceContextPropagator.HEADER_TRACE_ID)
        assertThat(headers[ITraceContextPropagator.HEADER_TRACE_ID]).hasSize(32)
        // W3C traceparent header is also injected by W3CTraceContextPropagator.
        assertThat(headers).containsKey("traceparent")
    }

    @Test
    fun `noop OpenTelemetry yields no span context`() {
        val otel = TracingBootstrap.buildOpenTelemetry(
            TracingConfig(
                enabled = false,
                backend = TracingConfig.Backend.CONSOLE,
                otlpEndpoint = "http://unused",
                serviceName = "astera-test",
            ),
        )
        val propagator = OpenTelemetryTraceContextPropagator(otel)

        val inside = propagator.withSpan("noop.span") { propagator.currentSpan() }
        // OpenTelemetry.noop() returns an invalid span context — propagator must
        // surface that as null rather than a non-null Fake handle.
        assertThat(inside).isNull()
    }
}
