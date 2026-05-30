package com.astarworks.astera.core.application.service.validation

import com.astarworks.astera.core.application.port.outbound.error.IErrorReporter
import com.astarworks.astera.core.application.port.outbound.lifecycle.IServerLifecycleListener
import com.astarworks.astera.core.application.port.outbound.validation.IContentValidator
import com.astarworks.astera.core.domain.error.ErrorSource
import com.astarworks.astera.core.domain.error.ServerError
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Boot-time hook (Wave 5server-harden / ADR-0123) that drives
 * [IContentValidator.validateAll] on `onReady()`.
 *
 * `onReady` is preferred over `onBoot` because every feature plugin's
 * `onEnable` registers its [com.astarworks.astera.core.application.port.outbound.validation.ContentReferenceCheck]
 * during boot; firing on `ready` guarantees every check is registered before
 * the validator walks them.
 *
 * # Mode gating
 *
 * - [Mode.Strict] — abort the boot by re-throwing on any
 *   [com.astarworks.astera.core.domain.validation.ContentValidationError]
 *   in the critical bucket (production / CI).
 * - [Mode.Lenient] — log all categories and continue (developer / staging).
 *
 * The mode is owned by config (`content.validation.mode` — yet to be wired
 * through `IConfig`; Phase 1 hard-codes the constructor default to
 * [Mode.Lenient] so first-pass dev workflow stays green while the report
 * lands in the log).
 */
public class BootValidateContentLifecycleListener(
    private val validator: IContentValidator,
    private val mode: Mode = Mode.Lenient,
    private val errorReporter: IErrorReporter? = null,
    private val clock: () -> Instant = { Instant.now() },
    private val logger: Logger = LoggerFactory.getLogger(
        BootValidateContentLifecycleListener::class.java,
    ),
) : IServerLifecycleListener {

    public enum class Mode { Strict, Lenient }

    /** Run early during `onReady` so a Strict abort happens before any
     *  player-facing path can fire. */
    override val priority: Int get() = HIGH_PRIORITY

    override fun onReady() {
        val report = validator.validateAll()
        logger.info(
            "Content validation: checked ${report.entriesChecked} entries across " +
                "${report.registriesChecked} registries — " +
                "${report.critical.size} critical / ${report.warning.size} warning / " +
                "${report.info.size} info",
        )

        report.critical.forEach { logger.error("CRITICAL [{}]: {}", it::class.simpleName, it.message) }
        report.warning.forEach { logger.warn("WARNING  [{}]: {}", it::class.simpleName, it.message) }
        report.info.forEach { logger.info("INFO     [{}]: {}", it::class.simpleName, it.message) }

        // Wave L 5hardening-complete (ADR-0129): structured fan-out per
        // ContentValidationError. Critical entries → ServerError.Critical
        // (admin broadcast + audit). Warning entries → ServerError.Warning
        // (audit-only). The boot-strict abort still fires below so the
        // operator sees the report on console + audit log + admin chat.
        errorReporter?.let { reporter ->
            val now = clock()
            report.critical.forEach { err ->
                reporter.report(
                    ServerError.Critical(
                        occurredAt = now,
                        summary = "Content validation: ${err.message}",
                        source = ErrorSource.Content(category = err::class.simpleName ?: "Validation"),
                        causeShortDescription = err::class.simpleName,
                    ),
                )
            }
            report.warning.forEach { err ->
                reporter.report(
                    ServerError.Warning(
                        occurredAt = now,
                        summary = "Content validation: ${err.message}",
                        source = ErrorSource.Content(category = err::class.simpleName ?: "Validation"),
                    ),
                )
            }
        }

        if (mode == Mode.Strict && report.hasCritical) {
            throw ContentValidationStrictFailure(report.critical.size)
        }
    }

    public class ContentValidationStrictFailure(
        public val criticalCount: Int,
    ) : IllegalStateException(
        "Content validation failed: $criticalCount critical error(s). Boot aborted (strict mode).",
    )

    private companion object {
        const val HIGH_PRIORITY = 1000
    }
}
