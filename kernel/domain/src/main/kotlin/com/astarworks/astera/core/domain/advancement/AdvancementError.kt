package com.astarworks.astera.core.domain.advancement

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Expected failure modes of advancement use cases (Wave 5p, ADR-0093).
 *
 * Each variant carries an i18n [messageKey] so commands / event listeners can
 * surface a localized message via `IMessageRenderer` without leaking domain
 * detail. Mirrors [com.astarworks.astera.core.domain.tutorial.TutorialError]'s
 * shape (Wave 5e).
 */
public sealed class AdvancementError {
    public abstract val messageKey: MessageKey

    public data class NotInCatalog(public val advancementId: AdvancementId) : AdvancementError() {
        override val messageKey: MessageKey =
            MessageKey("astera.advancement.error.not-in-catalog")
    }

    public data class InvalidIncrement(public val increment: Int) : AdvancementError() {
        override val messageKey: MessageKey =
            MessageKey("astera.advancement.error.invalid-increment")
    }

    public data class CriteriaShape(public val reason: String) : AdvancementError() {
        override val messageKey: MessageKey =
            MessageKey("astera.advancement.error.criteria-shape")
    }
}
