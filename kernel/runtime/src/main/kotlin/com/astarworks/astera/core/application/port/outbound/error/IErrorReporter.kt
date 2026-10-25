package com.astarworks.astera.core.application.port.outbound.error

import com.astarworks.astera.core.domain.error.ServerError

/**
 * Outbound port (Wave 5server-harden / ADR-0123): publish a structured
 * [ServerError].
 *
 * # Contract
 *
 * - `report(error)` is fire-and-forget. Implementations MUST NOT throw.
 * - Implementations MAY fan out: write to the audit log + invoke a
 *   [com.astarworks.astera.core.domain.error.ServerError.Severity.Critical]
 *   broadcast handler. Routing is impl-internal.
 * - Reports for [com.astarworks.astera.core.domain.error.ServerError.Severity.Info]
 *   are written at DEBUG severity in the audit log; Warning at WARN; Critical
 *   at ERROR + admin broadcast.
 *
 * # Subscriber
 *
 * `subscribeCritical(handler)` registers a callback fired when a
 * [com.astarworks.astera.core.domain.error.ServerError.Critical] is reported.
 * Returns a [Subscription] the caller cancels at `onDisable` — same shape
 * as [com.astarworks.astera.core.application.port.outbound.IDomainEventBus.Subscription]
 * to keep the cleanup pattern uniform across Astera.
 */
public interface IErrorReporter {

    public fun report(error: ServerError)

    public fun subscribeCritical(handler: (ServerError.Critical) -> Unit): Subscription

    public interface Subscription {
        public fun cancel()
        public val isActive: Boolean
    }
}
