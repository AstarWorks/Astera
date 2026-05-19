package com.astarworks.astera.combat.domain.skill.expression

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Sealed error returned by [ICelEvaluator.evaluate]. The Phase 1 Stub
 * always returns [NotYetImplemented]; real eval surfaces [ParseError] /
 * [EvalError] / [AttributeLookupFailed] depending on the failure.
 */
public sealed class CelError {
    public abstract val messageKey: MessageKey

    public data object NotYetImplemented : CelError() {
        override val messageKey: MessageKey = MessageKey("astera.combat.cel.error.not_yet_implemented")
    }
    public data class ParseError(val source: String, val message: String) : CelError() {
        override val messageKey: MessageKey = MessageKey("astera.combat.cel.error.parse")
    }
    public data class EvalError(val message: String) : CelError() {
        override val messageKey: MessageKey = MessageKey("astera.combat.cel.error.eval")
    }
    public data class AttributeLookupFailed(val path: String) : CelError() {
        override val messageKey: MessageKey = MessageKey("astera.combat.cel.error.attribute_lookup")
    }
}
