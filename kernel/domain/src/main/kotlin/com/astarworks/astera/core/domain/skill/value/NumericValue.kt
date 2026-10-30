package com.astarworks.astera.core.domain.skill.value

/**
 * Numeric value that can be either a literal Double or a CEL expression
 * evaluated by the skill engine at runtime.
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
