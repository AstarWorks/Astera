package com.astarworks.astera.core.domain.validation

/**
 * Aggregated output of a single [com.astarworks.astera.core.application.service.validation.ContentValidatorService]
 * pass.
 *
 * Carries one bucket per [ValidationSeverity] plus pre-computed counts so the
 * bootstrap log line ("validated N entries in M categories: K critical / L warning")
 * can be assembled without re-grouping at the call site.
 */
public data class ContentValidationReport(
    val critical: List<ContentValidationError>,
    val warning: List<ContentValidationError>,
    val info: List<ContentValidationError>,
    /** Number of distinct content entries inspected across every registry. */
    val entriesChecked: Int,
    /** Number of registries the validator walked (item / skill / shop / …). */
    val registriesChecked: Int,
) {

    public val totalErrors: Int get() = critical.size + warning.size + info.size

    public val hasCritical: Boolean get() = critical.isNotEmpty()

    public val hasAny: Boolean get() = totalErrors > 0

    /** All errors flattened in `critical → warning → info` order. */
    public fun allErrors(): List<ContentValidationError> = critical + warning + info

    public companion object {
        public val Empty: ContentValidationReport = ContentValidationReport(
            critical = emptyList(),
            warning = emptyList(),
            info = emptyList(),
            entriesChecked = 0,
            registriesChecked = 0,
        )
    }
}
