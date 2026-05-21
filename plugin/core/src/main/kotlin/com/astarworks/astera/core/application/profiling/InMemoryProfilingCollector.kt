package com.astarworks.astera.core.application.profiling

import com.astarworks.astera.core.application.port.outbound.profiling.IProfilingCollector
import com.astarworks.astera.core.application.port.outbound.profiling.ProfilingSample
import com.astarworks.astera.core.application.port.outbound.profiling.ProfilingSnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Default in-process [IProfilingCollector] (Wave 5u, ADR-0087).
 *
 * Accumulates samples into a (category, methodName) → (totalNanos, count)
 * map. Lock-free via [ConcurrentHashMap] + [AtomicLong] so hot-path
 * `ProfiledRunner.run` calls don't synchronise.
 *
 * The samples are intentionally lossy under high contention (counter +=
 * isn't atomic against another counter increment), but the goal is a
 * flamegraph that approximates the hot path — not a precise stopwatch.
 * Sub-nanosecond accuracy doesn't help a designer decide which use case is
 * 10× slower than expected.
 */
public class InMemoryProfilingCollector : IProfilingCollector {

    private data class Bucket(val totalNanos: AtomicLong, val invocationCount: AtomicLong)

    private val samples: MutableMap<Pair<String, String>, Bucket> = ConcurrentHashMap()

    override fun record(category: String, methodName: String, durationNanos: Long) {
        val bucket = samples.computeIfAbsent(category to methodName) {
            Bucket(AtomicLong(0), AtomicLong(0))
        }
        bucket.totalNanos.addAndGet(durationNanos)
        bucket.invocationCount.incrementAndGet()
    }

    override fun export(): ProfilingSnapshot {
        val list = samples.entries.map { (key, bucket) ->
            ProfilingSample(
                category = key.first,
                methodName = key.second,
                totalNanos = bucket.totalNanos.get(),
                invocationCount = bucket.invocationCount.get(),
            )
        }
        return ProfilingSnapshot(list)
    }

    override fun reset() {
        samples.clear()
    }
}
