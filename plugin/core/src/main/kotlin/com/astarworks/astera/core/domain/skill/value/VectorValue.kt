package com.astarworks.astera.core.domain.skill.value

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * 3D vector value that can be either a literal [Vec3] or a CEL expression
 * resolved at runtime. Phase 1 evaluator (StubCelEvaluator) returns Failure
 * for [Cel]; dispatcher catches + warn-skips. Phase 6 lands the real
 * evaluator and Cel values activate automatically.
 *
 * Wave _skills-2 / ADR-0077.
 */
public sealed class VectorValue {
    public data class Literal(public val value: Vec3) : VectorValue()
    public data class Cel(
        public val script: String,
        public val referencedBindings: List<BindingRef> = emptyList(),
    ) : VectorValue() {
        init {
            require(script.isNotBlank()) { "VectorValue.Cel script must not be blank" }
        }
    }
}
