package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Sealed error hierarchy for `application/config/` kaml DTO → domain
 * conversions (Wave 5c+; promoted from Match to Core in Wave 5d).
 *
 * Per `coding-posture.md` §"Application API surface", application APIs return
 * `Result<T, E>` rather than throwing raw JVM throwables. This is the shared
 * `E` for every DTO that converts to a domain type — `GeneratorYamlConfig`,
 * `AnomalyYamlConfig`, `BlockDurabilityYamlConfig`, and future loaders.
 * Wave 5d promoted the type to Core so Core-local DTOs (e.g. block durability)
 * could use it without depending on Match.
 *
 * `StageYamlConfig.toSpec` (pre-existing Wave 2 code) still throws
 * `IllegalArgumentException`; aligning that file is tracked separately to
 * avoid bundling unrelated changes.
 *
 * Each variant carries the field name + offending raw value so loaders can
 * surface authoring mistakes with file context.
 */
public sealed class ConfigError {
    public abstract val messageKey: MessageKey
    public abstract val detail: String

    /** Field value is outside its declared enum / accepted set. */
    public data class UnknownEnumValue(
        val field: String,
        val rawValue: String,
        val accepted: List<String>,
    ) : ConfigError() {
        override val messageKey: MessageKey =
            MessageKey("astera.config.error.unknown-enum-value")
        override val detail: String =
            "Unknown $field value '$rawValue' (expected one of: ${accepted.joinToString()})"
    }

    /** Numeric field is at or below the documented minimum. */
    public data class OutOfRange(
        val field: String,
        val rawValue: String,
        val constraint: String,
    ) : ConfigError() {
        override val messageKey: MessageKey =
            MessageKey("astera.config.error.out-of-range")
        override val detail: String =
            "$field='$rawValue' violates: $constraint"
    }

    /** Two fields disagree (e.g. `affiliation.kind=neutral` carries a `team_id`). */
    public data class FieldShapeMismatch(
        val field: String,
        val detailMessage: String,
    ) : ConfigError() {
        override val messageKey: MessageKey =
            MessageKey("astera.config.error.field-shape-mismatch")
        override val detail: String = "$field: $detailMessage"
    }

    /** Identifier (id / team_id / attribute key) failed its lexical validation. */
    public data class InvalidIdentifier(
        val field: String,
        val rawValue: String,
        val constraint: String,
    ) : ConfigError() {
        override val messageKey: MessageKey =
            MessageKey("astera.config.error.invalid-identifier")
        override val detail: String =
            "$field='$rawValue' is not a valid identifier ($constraint)"
    }
}
