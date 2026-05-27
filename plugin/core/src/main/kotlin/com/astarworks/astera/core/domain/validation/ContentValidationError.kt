package com.astarworks.astera.core.domain.validation

/**
 * Sealed failure modes emitted by [com.astarworks.astera.core.application.service.validation.ContentValidatorService]
 * when it walks a Contents registry snapshot at boot (Wave 5server-harden / ADR-0123).
 *
 * Each variant carries enough structural detail to render a human-readable
 * diagnostic without re-parsing the source YAML — the validator's job is to
 * surface integrity problems, not to recover from them.
 *
 * # Why a sealed class instead of a single string message
 *
 * Operators iterate "fix one missing reference, re-validate, fix the next" —
 * a grouped report (N MissingReference + M DuplicateId) reads better than a
 * stack of free-form lines. A sealed hierarchy also lets the bootstrap hook
 * count by category for the metric / log line.
 */
public sealed class ContentValidationError {

    /** Identifier of the content entry that owns the problem (e.g. `"shop:starter"`). */
    public abstract val sourceId: String

    /** Short human-readable summary suitable for an admin log line. */
    public abstract val message: String

    /**
     * A reference (id) inside [sourceId] points at an entry that does not
     * exist in the target registry.
     *
     * @property referenceKind logical name of the target — `"item"`, `"skill"`,
     *   `"mob"`, `"block"`, `"stage"`, etc.
     * @property referencedId the missing id, as a plain string (each domain id
     *   value-class renders to its string form via `.value`).
     * @property field path inside the source entry where the reference lives
     *   (e.g. `"offers[2].itemId"`).
     */
    public data class MissingReference(
        override val sourceId: String,
        val referenceKind: String,
        val referencedId: String,
        val field: String,
    ) : ContentValidationError() {
        override val message: String =
            "$sourceId.$field references unknown $referenceKind '$referencedId'"
    }

    /**
     * Two entries in the same Contents registry share the same id. Detected
     * by the validator independent of any registry-side `register` semantics
     * (the warning still fires even if the registry happens to be last-write-wins).
     */
    public data class DuplicateId(
        override val sourceId: String,
        val registryName: String,
    ) : ContentValidationError() {
        override val message: String = "duplicate id '$sourceId' in $registryName registry"
    }

    /**
     * Schema-level invariant breakage that the YAML loader allowed through
     * (e.g. a structural shape passes kaml but the domain `init` block would
     * throw — the validator catches it pre-runtime).
     */
    public data class InvalidSchema(
        override val sourceId: String,
        val reason: String,
    ) : ContentValidationError() {
        override val message: String = "$sourceId: $reason"
    }
}

/**
 * Severity tier used to gate "fail-fast on boot" vs "log + continue".
 *
 * The strict/lenient switch on the bootstrap hook maps Critical → abort;
 * Warning → log; Info → debug-log.
 */
public enum class ValidationSeverity { Critical, Warning, Info }
