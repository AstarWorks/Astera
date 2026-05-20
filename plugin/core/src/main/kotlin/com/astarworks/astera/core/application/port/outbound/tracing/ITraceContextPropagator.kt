package com.astarworks.astera.core.application.port.outbound.tracing

/**
 * Outbound port: distributed-tracing context propagation (Wave 5u, ADR-0087).
 *
 * Vendor-neutral wrapper over a tracing SDK (default impl uses OpenTelemetry
 * but the port itself does NOT import `io.opentelemetry.api.*` — that
 * import lives in the adapter layer per Konsist `core.application must not
 * import io.opentelemetry.api`).
 *
 * silly-knuth §5: tracing is a *side-channel*. Operations cannot fail in a
 * way the caller can act on — a missing tracer collapses to no-op spans, an
 * exporter outage drops samples server-side. So no `Result<T, E>` returns.
 *
 * # Cross-server propagation
 *
 * [injectIntoMessage] / [extractFromMessage] are the proxy-messaging
 * integration points: when game-pod A sends a player to game-pod B via the
 * Wave 5r `IProxyMessenger`, the headers map carries the active trace
 * context across the network hop so Jaeger / Zipkin can stitch spans into
 * a single trace.
 *
 * Wave 5r is in flight; this port lands ahead of the messenger so 5r can
 * wire the integration without a port-side change.
 */
public interface ITraceContextPropagator {

    /**
     * Returns an opaque handle to the current span context, or `null` if no
     * span is active on the calling thread. Used by adapters that need to
     * stash the context for later resumption (proxy messaging,
     * scheduler-deferred work).
     */
    public fun currentSpan(): SpanHandle?

    /**
     * Runs [block] inside a span named [name] with the given [attrs] attached.
     * The span ends when [block] returns (normally or via exception). Spans
     * created here are children of whatever span is active on the calling
     * thread; if none is active, this becomes a new root span.
     */
    public fun <T> withSpan(name: String, attrs: Map<String, String> = emptyMap(), block: () -> T): T

    /**
     * Serialises the current trace context into [headers] for transmission
     * over the [channel] (proxy messaging channel name, e.g.
     * `"astera:reward-credit"`). Called on the *send* side; the matching
     * `extractFromMessage` runs on the *receive* side.
     *
     * Wave 5u v0: HTTP/W3C trace-context header format
     * (`traceparent` / `tracestate`). Wave 5r will commit the actual proxy
     * envelope schema; until then the implementation uses a single
     * `X-Astera-Trace-Id` header derived from the W3C trace ID.
     */
    public fun injectIntoMessage(channel: String, headers: MutableMap<String, String>)

    /**
     * Reconstructs a trace context from incoming [headers] and makes it the
     * active context for [block]. Mirror of [injectIntoMessage]. If no
     * trace headers are present, [block] runs with no inherited context
     * (becomes a new root span tree).
     */
    public fun <T> extractFromMessage(
        channel: String,
        headers: Map<String, String>,
        block: () -> T,
    ): T

    public companion object {
        /**
         * Header name used by the v0 single-header propagation path. Once
         * Wave 5r commits its messaging envelope schema this constant moves
         * with the propagator's W3C-compliant default.
         */
        public const val HEADER_TRACE_ID: String = "X-Astera-Trace-Id"
    }
}

/**
 * Opaque carrier for a span handle. Adapters can downcast to their concrete
 * type ([com.astarworks.astera.core.adapter.paper.tracing.OpenTelemetrySpanHandle]
 * exposes the underlying OTel `Span`) but application code treats it as
 * opaque.
 */
public interface SpanHandle {
    /** Hex-encoded W3C trace id (16 bytes → 32 hex chars). Used for log correlation. */
    public val traceId: String

    /** Hex-encoded W3C span id (8 bytes → 16 hex chars). */
    public val spanId: String
}
