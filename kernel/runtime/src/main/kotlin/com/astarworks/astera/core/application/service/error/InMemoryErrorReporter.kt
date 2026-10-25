package com.astarworks.astera.core.application.service.error

import com.astarworks.astera.core.application.port.outbound.IAuditLogRepository
import com.astarworks.astera.core.application.port.outbound.error.IErrorReporter
import com.astarworks.astera.core.domain.audit.AuditEvent
import com.astarworks.astera.core.domain.audit.AuditEventId
import com.astarworks.astera.core.domain.error.ServerError
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Default in-process implementation of [IErrorReporter] (Wave 5server-harden /
 * ADR-0123).
 *
 * Fan-out per [com.astarworks.astera.core.domain.error.ServerError.Severity]:
 *  - all severities: log at SLF4J ERROR/WARN/DEBUG + append to audit log
 *  - Critical only: invoke every registered subscriber, swallowing thrown
 *    exceptions so one buggy admin handler can't mask the audit trail or
 *    block another subscriber.
 *
 * The audit log write is best-effort: an exception from the repository is
 * caught + logged at WARN, since the alternative (rethrowing) would defeat
 * the "MUST NOT throw" contract on [report] and could mask the original
 * error report.
 */
public class InMemoryErrorReporter(
    private val auditLog: IAuditLogRepository,
    private val logger: Logger = LoggerFactory.getLogger(InMemoryErrorReporter::class.java),
) : IErrorReporter {

    private val criticalSubscribers = CopyOnWriteArrayList<CriticalSub>()

    override fun report(error: ServerError) {
        logError(error)
        writeAuditSafe(error)
        if (error is ServerError.Critical) {
            for (sub in criticalSubscribers) {
                if (sub.isActive) sub.invokeSafe(error)
            }
        }
    }

    private fun logError(error: ServerError) {
        val location = error.source.identifier
        when (error) {
            is ServerError.Critical -> {
                val cause = error.causeShortDescription?.let { " (cause: $it)" }.orEmpty()
                logger.error("[{}] CRITICAL: {}{}", location, error.summary, cause)
            }
            is ServerError.Warning -> {
                logger.warn("[{}] WARNING: {} ctx={}", location, error.summary, error.context)
            }
            is ServerError.Info -> {
                logger.debug("[{}] INFO: {} ctx={}", location, error.summary, error.context)
            }
        }
    }

    private fun writeAuditSafe(error: ServerError) {
        try {
            auditLog.write(
                AuditEvent.ServerErrorReported(
                    id = AuditEventId.random(),
                    severity = error.severity.name,
                    summary = error.summary,
                    sourceIdentifier = error.source.identifier,
                    occurredAt = error.occurredAt,
                ),
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            logger.warn(
                "Failed to write ServerError to audit log ({}): {}",
                e::class.simpleName,
                e.message,
            )
        }
    }

    override fun subscribeCritical(handler: (ServerError.Critical) -> Unit): IErrorReporter.Subscription {
        val sub = CriticalSub(handler, logger)
        criticalSubscribers += sub
        return object : IErrorReporter.Subscription {
            override val isActive: Boolean get() = sub.isActive
            override fun cancel() {
                if (sub.isActive) {
                    sub.cancel()
                    criticalSubscribers.remove(sub)
                }
            }
        }
    }

    /** Test/utility: count of currently active critical subscribers. */
    public fun subscriberCount(): Int = criticalSubscribers.size

    private class CriticalSub(
        private val handler: (ServerError.Critical) -> Unit,
        private val logger: Logger,
    ) {
        @Volatile
        private var active = true

        val isActive: Boolean get() = active

        fun invokeSafe(error: ServerError.Critical) {
            try {
                handler(error)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                logger.warn(
                    "Critical-error subscriber threw ({}): {}",
                    e::class.simpleName,
                    e.message,
                )
            }
        }

        fun cancel() {
            active = false
        }
    }
}
