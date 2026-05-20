package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.tracing.ITraceContextPropagator
import com.astarworks.astera.core.application.port.outbound.tracing.SpanHandle

/**
 * Test fake for [ITraceContextPropagator] (Wave 5u, ADR-0087).
 *
 * Records every `withSpan(name, attrs)` invocation into [recordedSpans] so
 * tests can assert "X use case opened a span called 'match.start' with attrs
 * { matchId: ... }".
 *
 * Cross-server propagation paths use a simple `X-Astera-Trace-Id` header and
 * a monotonic in-memory counter so tests can verify "extract → withSpan
 * sees the same trace id" without an SDK dep.
 */
public class FakeTraceContextPropagator : ITraceContextPropagator {

    public data class RecordedSpan(val name: String, val attrs: Map<String, String>)

    public val recordedSpans: MutableList<RecordedSpan> = mutableListOf()
    public val injectedHeaders: MutableList<Map<String, String>> = mutableListOf()
    public val extractedHeaders: MutableList<Map<String, String>> = mutableListOf()

    private var nextTraceId = 1L
    private var currentTrace: ThreadLocal<String?> = ThreadLocal()

    override fun currentSpan(): SpanHandle? {
        val trace = currentTrace.get() ?: return null
        return FakeSpanHandle(traceId = trace, spanId = trace)
    }

    override fun <T> withSpan(name: String, attrs: Map<String, String>, block: () -> T): T {
        recordedSpans += RecordedSpan(name, attrs)
        val previous = currentTrace.get()
        if (previous == null) {
            currentTrace.set("trace-%016x".format(nextTraceId++))
        }
        return try {
            block()
        } finally {
            if (previous == null) currentTrace.remove() else currentTrace.set(previous)
        }
    }

    override fun injectIntoMessage(channel: String, headers: MutableMap<String, String>) {
        injectedHeaders += headers.toMap()
        currentTrace.get()?.let {
            headers[ITraceContextPropagator.HEADER_TRACE_ID] = it
        }
    }

    override fun <T> extractFromMessage(
        channel: String,
        headers: Map<String, String>,
        block: () -> T,
    ): T {
        extractedHeaders += headers
        val incoming = headers[ITraceContextPropagator.HEADER_TRACE_ID]
        val previous = currentTrace.get()
        if (incoming != null) currentTrace.set(incoming)
        return try {
            block()
        } finally {
            if (previous == null) currentTrace.remove() else currentTrace.set(previous)
        }
    }

    private data class FakeSpanHandle(
        override val traceId: String,
        override val spanId: String,
    ) : SpanHandle
}
