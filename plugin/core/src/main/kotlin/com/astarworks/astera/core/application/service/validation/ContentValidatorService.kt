package com.astarworks.astera.core.application.service.validation

import com.astarworks.astera.core.application.port.outbound.validation.ContentReferenceCheck
import com.astarworks.astera.core.application.port.outbound.validation.IContentValidator
import com.astarworks.astera.core.domain.validation.ContentValidationError
import com.astarworks.astera.core.domain.validation.ContentValidationReport
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
 */
public class ContentValidatorService : IContentValidator {

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
                listOf(
                    ContentValidationError.InvalidSchema(
                        sourceId = check::class.simpleName ?: "<anonymous-check>",
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
