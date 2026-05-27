package com.astarworks.astera.testfixtures.error

import com.astarworks.astera.core.application.port.outbound.error.IErrorReporter
import com.astarworks.astera.core.domain.error.ServerError
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Recording fake of [IErrorReporter] for tests (Wave 5server-harden / ADR-0123).
 *
 * Captures every reported [ServerError] into [reports] and routes Critical
 * variants through any subscriber registered via [subscribeCritical]. Unlike
 * the production InMemoryErrorReporter:
 *
 *  - no SLF4J writes,
 *  - no audit log dependency,
 *  - exceptions thrown by subscribers propagate to the test (so a buggy
 *    handler shows up as a failure rather than being silently swallowed —
 *    the production swallowing happens behind the SLF4J warn).
 */
public class FakeErrorReporter : IErrorReporter {

    public val reports: MutableList<ServerError> = mutableListOf()
    private val subs = CopyOnWriteArrayList<FakeSub>()

    override fun report(error: ServerError) {
        reports += error
        if (error is ServerError.Critical) {
            for (s in subs) if (s.isActive) s.handler(error)
        }
    }

    override fun subscribeCritical(handler: (ServerError.Critical) -> Unit): IErrorReporter.Subscription {
        val sub = FakeSub(handler)
        subs += sub
        return sub
    }

    public fun clear() {
        reports.clear()
    }

    public fun subscriberCount(): Int = subs.size

    private inner class FakeSub(val handler: (ServerError.Critical) -> Unit) : IErrorReporter.Subscription {
        @Volatile
        private var active = true
        override val isActive: Boolean get() = active
        override fun cancel() {
            if (active) {
                active = false
                subs.remove(this)
            }
        }
    }
}
