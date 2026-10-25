package com.astarworks.astera.core.domain.skill.value

/**
 * Numeric value that can be either a literal Double or a CEL expression
 * resolved at runtime. Phase 1 evaluator (StubCelEvaluator) returns Failure
 * for [Cel]; dispatcher catches + warn-skips. Phase 6 lands the real
 * evaluator and Cel values activate automatically.
 *
 * Wave _skills-2 / ADR-0077.
 */
public sealed class NumericValue {
    public data class Literal(public val value: Double) : NumericValue()
    public data class Cel(
        public val script: String,
        public val referencedBindings: List<BindingRef> = emptyList(),
    ) : NumericValue() {
        init {
            require(script.isNotBlank()) { "NumericValue.Cel script must not be blank" }
        }
    }
}
