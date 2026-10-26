package com.astarworks.astera.core.application.service.observability

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/** On-demand, lock-free recording; no timer, task or idle polling. Durations are inclusive. */
public object ExecutionCosts {
    private data class Key(val kind: String, val id: String)
    private class Family {
        val total = Histogram()
        val ids = ConcurrentHashMap<String, Histogram>()
    }
    private val counters = ConcurrentHashMap<String, LongAdder>()

    public fun increment(name: String, amount: Long = 1) {
        counters.computeIfAbsent(name) {
            require(it.matches(Regex("[a-z_]+")))
            LongAdder()
        }.add(amount)
    }

    private val families = ConcurrentHashMap<String, Family>()

    public inline fun <T> measure(kind: String, id: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(kind, id, System.nanoTime() - start)
        }
    }

    public fun record(kind: String, id: String, nanos: Long) {
        val family = families.computeIfAbsent(kind) { Family() }
        val duration = nanos.coerceAtLeast(0)
        family.total.record(duration)
        family.ids.computeIfAbsent(id) { Histogram() }.record(duration)
    }

    /** Approximate power-of-two bucket upper bounds; max is exact. CPS is since first observation. */
    public fun scrape(): String = buildString {
        for ((name, value) in counters.entries.sortedBy { it.key }) {
            append("astera_").append(name).append("_total ").append(value.sum()).append('\n')
        }
        val snapshots = families.entries.flatMap { (kind, family) ->
            listOf(Key(kind, "*") to family.total) + family.ids.entries.map { Key(kind, it.key) to it.value }
        }.sortedWith(compareBy({ it.first.kind }, { it.first.id }))
        for ((key, h) in snapshots) {
            val tags = "kind=\"${escape(key.kind)}\",id=\"${escape(key.id)}\""
            val buckets = LongArray(64) { h.buckets[it].sum() }
            val calls = buckets.sum()
            fun sample(name: String, value: Number, extra: String = "") {
                append("astera_execution_").append(name).append('{').append(tags).append(extra)
                    .append("} ").append(value).append('\n')
            }
            for (q in listOf(0.50, 0.95, 0.99)) {
                val rank = kotlin.math.ceil(calls * q).toLong()
                var cumulative = 0L
                val bucket = buckets.indexOfFirst { cumulative += it; cumulative >= rank }.coerceAtLeast(0)
                val upper = if (bucket >= 63) Long.MAX_VALUE else (1L shl bucket) - 1
                sample("nanoseconds", upper, ",quantile=\"$q\"")
            }
            sample("max_nanoseconds", h.max.get())
            sample("calls_total", calls)
            sample("calls_per_second", calls * 1e9 / (System.nanoTime() - h.started).coerceAtLeast(1))
        }
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private class Histogram {
        val started = System.nanoTime()
        val buckets = Array(64) { LongAdder() }
        val max = AtomicLong()
        fun record(nanos: Long) {
            buckets[64 - java.lang.Long.numberOfLeadingZeros(nanos)].increment()
            var previous = max.get()
            while (nanos > previous && !max.compareAndSet(previous, nanos)) previous = max.get()
        }
    }
}
