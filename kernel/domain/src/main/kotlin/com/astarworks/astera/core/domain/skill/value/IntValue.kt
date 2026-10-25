package com.astarworks.astera.core.domain.skill.value

/**
 * Integer value that can be either a literal Int or a CEL expression
 * resolved at runtime. Phase 1 evaluator (StubCelEvaluator) returns Failure
 * for [Cel]; dispatcher catches + warn-skips. Phase 6 lands the real
 * evaluator and Cel values activate automatically.
 *
 * Wave _skills-2 / ADR-0077.
 */
public sealed class IntValue {
    public data class Literal(public val value: Int) : IntValue()
    public data class Cel(
        public val script: String,
        public val referencedBindings: List<BindingRef> = emptyList(),
    ) : IntValue() {
        init {
            require(script.isNotBlank()) { "IntValue.Cel script must not be blank" }
        }
    }
}
