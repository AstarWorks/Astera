package com.astarworks.astera.core.adapter.paper.tracing

import com.astarworks.astera.core.application.port.outbound.tracing.ITraceContextPropagator
import com.astarworks.astera.core.application.port.outbound.tracing.SpanHandle
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter
import org.slf4j.LoggerFactory

/**
 * OpenTelemetry-backed [ITraceContextPropagator] (Wave 5u, ADR-0087).
 *
 * Lives in `core.adapter.paper.tracing` per Konsist's
 * "core.application must not import io.opentelemetry.api" rule (introduced
 * by Wave 5u's two new Konsist rules; see ArchitectureTest).
 *
 * # Wiring
 *
 * `OpenTelemetry` is provided at construction time. Two production paths:
 *  1. `OpenTelemetrySdk.builder()` + OTLP exporter (Jaeger / Zipkin via
 *     collector) — production
 *  2. Console exporter via `LoggingSpanExporter` — local dev
 *
 * `AsteraCorePlugin.onEnable` decides which to wire based on env vars; see
 * `TracingConfig.kt`.
 *
 * # Threading
 *
 * `withSpan` uses `Span.makeCurrent()` which installs the span as
 * thread-local. Paper's main thread + scheduler thread are the only spots
 * we wrap; Paper-Folia regional thread context is **not** propagated
 * automatically — that requires Folia-aware adapter work deferred to
 * Phase 2 (see `.claude/plans/idea-observability-depth.md` §B).
 */
public class OpenTelemetryTraceContextPropagator(
    public val openTelemetry: OpenTelemetry,
    instrumentationName: String = "com.astarworks.astera",
) : ITraceContextPropagator {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tracer: Tracer = openTelemetry.getTracer(instrumentationName)
    private val propagators = openTelemetry.propagators.textMapPropagator

    override fun currentSpan(): SpanHandle? {
        val span = Span.current()
        val ctx = span.spanContext
        if (!ctx.isValid) return null
        return OpenTelemetrySpanHandle(span)
    }

    override fun <T> withSpan(name: String, attrs: Map<String, String>, block: () -> T): T {
        val builder = tracer.spanBuilder(name)
        for ((k, v) in attrs) builder.setAttribute(k, v)
        val span = builder.startSpan()
        return try {
            span.makeCurrent().use { _ ->
                block()
            }
        } catch (t: Throwable) {
            span.recordException(t)
            throw t
        } finally {
            span.end()
        }
    }

    override fun injectIntoMessage(channel: String, headers: MutableMap<String, String>) {
        propagators.inject(Context.current(), headers, MapTextSetter)
        // Also surface the trace id under the Astera-specific header so
        // proxy-messenger consumers that only need correlation (not full
        // propagation) can read it without a W3C parser.
        Span.current().spanContext.takeIf { it.isValid }?.let {
            headers[ITraceContextPropagator.HEADER_TRACE_ID] = it.traceId
        }
    }

    override fun <T> extractFromMessage(
        channel: String,
        headers: Map<String, String>,
        block: () -> T,
    ): T {
        val extracted = propagators.extract(Context.current(), headers, MapTextGetter)
        return extracted.makeCurrent().use { _ ->
            block()
        }
    }

    private object MapTextSetter : TextMapSetter<MutableMap<String, String>> {
        override fun set(carrier: MutableMap<String, String>?, key: String, value: String) {
            carrier?.put(key, value)
        }
    }

    private object MapTextGetter : TextMapGetter<Map<String, String>> {
        override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys
        override fun get(carrier: Map<String, String>?, key: String): String? = carrier?.get(key)
    }

    init {
        log.debug("OpenTelemetryTraceContextPropagator initialised (tracer={})", instrumentationName)
    }
}

/**
 * Concrete [SpanHandle] backed by an OpenTelemetry [Span]. Adapters that need
 * to attach more attributes after the fact can downcast and call methods on
 * [span] directly.
 */
public class OpenTelemetrySpanHandle(public val span: Span) : SpanHandle {
    override val traceId: String get() = span.spanContext.traceId
    override val spanId: String get() = span.spanContext.spanId
}
