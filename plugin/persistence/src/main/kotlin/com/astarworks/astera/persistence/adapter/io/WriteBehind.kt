package com.astarworks.astera.persistence.adapter.io

import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Idempotent snapshots only. A single IO worker preserves order across aggregates and players. */
public class WriteBehind : AutoCloseable {
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "astera-persistence-io").apply { isDaemon = true }
    }
    private val pending = linkedMapOf<Any, () -> Unit>()
    private var accepting = true
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        worker.scheduleWithFixedDelay({
            runCatching { drain() }.onFailure { log.error("Persistence flush failed; snapshots retained for retry", it) }
        }, 1, 1, TimeUnit.SECONDS)
    }

    /** Capture immutable state before calling this method; never read an entity-owned object on IO. */
    @Synchronized
    public fun enqueue(key: Any, write: () -> Unit) {
        check(accepting) { "Persistence is closing" }
        pending.remove(key)
        pending[key] = write
    }

    /** Also acts as a barrier before a player transfers to another backend. */
    public fun flush(): CompletableFuture<Void> = CompletableFuture.runAsync({ drain() }, worker)

    public fun <T> read(block: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync({ drain(); IoThread.run(block) }, worker)

    private fun drain() {
        while (true) {
            val next = synchronized(this) { pending.entries.firstOrNull()?.let { it.key to it.value } } ?: return
            var failure: Throwable? = null
            for (attempt in 1..3) {
                try {
                    IoThread.run(next.second)
                    synchronized(this) { if (pending[next.first] === next.second) pending.remove(next.first) }
                    break
                } catch (ex: Exception) {
                    failure = ex
                }
            }
            // A failed older snapshot must not be passed by a later write.
            if (synchronized(this) { pending[next.first] === next.second }) throw checkNotNull(failure)
        }
    }

    override fun close() {
        synchronized(this) { accepting = false }
        try {
            flush().join() // shutdown waits for the IO barrier before the pool can close
        } finally {
            worker.shutdown()
            check(worker.awaitTermination(30, TimeUnit.SECONDS)) { "Persistence IO worker did not terminate" }
        }
    }
}
