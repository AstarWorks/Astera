package com.astarworks.astera.core.application.service.validation

import com.astarworks.astera.core.application.port.outbound.error.IErrorReporter
import com.astarworks.astera.core.application.port.outbound.validation.ContentReferenceCheck
import com.astarworks.astera.core.application.port.outbound.validation.IContentValidator
import com.astarworks.astera.core.domain.error.ErrorSource
import com.astarworks.astera.core.domain.error.ServerError
import com.astarworks.astera.core.domain.validation.ContentValidationError
import com.astarworks.astera.core.domain.validation.ContentValidationReport
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Default in-process implementation of [IContentValidator] (Wave 5server-harden / ADR-0123).
 *
 * Holds a thread-safe list of registered [ContentReferenceCheck]s (plugins
 * register at `onEnable`, unregister at `onDisable`). `validateAll()` invokes
 * each check, catches any unexpected exception per-check so one buggy check
 * cannot mask the others, and folds the results into a single
 * [ContentValidationReport].
 *
 * # Severity bucketing
 *
 * The validator itself is policy-free: it routes by error variant.
 *
 * - [ContentValidationError.MissingReference] → critical (a real
 *   `unresolved` link)
 * - [ContentValidationError.DuplicateId] → warning (registry is
 *   last-write-wins, so the load still produces a valid graph)
 * - [ContentValidationError.InvalidSchema] → critical (the entry is broken
 *   independent of references)
 *
 * Operators flip strict / lenient at the bootstrap hook layer
 * ([com.astarworks.astera.core.application.service.validation.BootValidateContentLifecycleListener])
 * — this service does not gate the boot itself.
 *
 * # Wave L 5hardening-complete (ADR-0129) — error fan-out
 *
 * An optional [IErrorReporter] dependency lets the service forward a
 * structured [ServerError.Critical] when a check throws (the per-check
 * `try/catch` arm). Without a reporter, the throw is still captured as a
 * `ContentValidationError.InvalidSchema` and rolled into the report (no
 * behavior change vs the pre-Wave-L contract); with a reporter, admin
 * broadcast / audit log fan-out happens immediately, before the boot
 * lifecycle listener sees the report. The reporter is parameter-optional
 * for backwards source-compatibility with the Wave 5server-harden test
 * suite (which constructs the service without one).
 */
public class ContentValidatorService(
    private val errorReporter: IErrorReporter? = null,
    private val clock: () -> Instant = { Instant.now() },
) : IContentValidator {

    private val checks = CopyOnWriteArrayList<ContentReferenceCheck>()

    override fun validateAll(): ContentValidationReport {
        val critical = mutableListOf<ContentValidationError>()
        val warning = mutableListOf<ContentValidationError>()
        val info = mutableListOf<ContentValidationError>()

        var entriesChecked = 0
        for (check in checks) {
            val errors = try {
                check.check()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                // Wave L (ADR-0129): a thrown check is structurally a
                // Critical content-loader failure. Report it before
                // synthesising the InvalidSchema entry so operator broadcast
                // happens even if the caller never inspects the report.
                val sourceName = check::class.simpleName ?: "<anonymous-check>"
                errorReporter?.report(
                    ServerError.Critical(
                        occurredAt = clock(),
                        summary = "ContentReferenceCheck '$sourceName' threw during validateAll",
                        source = ErrorSource.Content(category = sourceName),
                        causeShortDescription = "${e::class.simpleName}: ${e.message ?: "<no message>"}",
                    ),
                )
                listOf(
                    ContentValidationError.InvalidSchema(
                        sourceId = sourceName,
                        reason = "check threw: ${e.message ?: e::class.simpleName}",
                    ),
                )
            }
            entriesChecked += errors.size.coerceAtLeast(1)
            for (e in errors) {
                when (e) {
                    is ContentValidationError.MissingReference -> critical += e
                    is ContentValidationError.InvalidSchema -> critical += e
                    is ContentValidationError.DuplicateId -> warning += e
                }
            }
        }
        return ContentValidationReport(
            critical = critical,
            warning = warning,
            info = info,
            entriesChecked = entriesChecked,
            registriesChecked = checks.size,
        )
    }

    override fun register(check: ContentReferenceCheck): IContentValidator.Registration {
        checks += check
        return object : IContentValidator.Registration {
            override fun unregister() {
                checks.remove(check)
            }
        }
    }

    /** Test/utility: clear all registrations. */
    public fun reset() {
        checks.clear()
    }

    /** Test/utility: count of currently registered checks. */
    public fun registeredCount(): Int = checks.size
}
