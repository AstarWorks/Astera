package com.astarworks.astera.core.domain.damage

import com.astarworks.astera.core.domain.skill.value.NumericValue

/**
 * Pure damage descriptor — now a non-empty list of [DamageComponent]s so a
 * single mechanic can deal mixed-attribute damage (e.g. a fire-electric
 * arrow contributes both a `FIRE` component and an `ELECTRIC` component
 * routed through the element matrix independently).
 *
 * Combat math lives in `DamageRule` (pluggable modifier pipeline in
 * `:plugin:combat`, see ADR-0030); this is just the serializable,
 * side-effect-free input.
 *
 * Wave _skills-2 (ADR-0077) widens the shape from `(base, attribute)` to
 * `List<DamageComponent>`. Use the [single] factory for the common
 * one-component case so call sites keep `DamageProfile.single(8.0, FIRE)`
 * readability.
 *
 * Lives in `:plugin:core` since Wave _items-1 (ADR-0073): every feature
 * plugin (Items, Weapons, Combat) references the profile from inline item
 * specs.
 */
public data class DamageProfile(
    public val components: List<DamageComponent>,
) {
    init {
        require(components.isNotEmpty()) { "DamageProfile.components must not be empty" }
    }

    /**
     * Phase 1 single-attribute pipeline compatibility: the first component's
     * attribute. The combat damage pipeline (ArmorReductionModifier /
     * ElementMatrix / WorkingDamage / DamageResolution) still uses one
     * attribute per attempt; Phase 6 may rework the pipeline to iterate every
     * component. Until then, the primary component drives the lookup.
     */
    public val attribute: DamageAttribute
        get() = components.first().attribute

    /**
     * Phase 1 single-base pipeline compatibility: sum of all components'
     * literal base values, with `multiplier` applied. CEL bases evaluate to
     * 0.0 until the real evaluator lands in Phase 6 (the dispatcher emits a
     * warn-log when this happens; the damage pipeline never sees a CEL
     * value directly).
     */
    public val base: Double
        get() = components.sumOf { component ->
            val baseValue = (component.base as? NumericValue.Literal)?.value ?: 0.0
            val mult = (component.multiplier as? NumericValue.Literal)?.value ?: 1.0
            baseValue * mult
        }

    public companion object {
        /** Single-component convenience for the common case. */
        public fun single(base: Double, attribute: DamageAttribute): DamageProfile =
            DamageProfile(
                listOf(
                    DamageComponent(
                        base = NumericValue.Literal(base),
                        attribute = attribute,
                    ),
                ),
            )
    }
}

/**
 * One attribute-typed damage band inside a [DamageProfile].
 *
 *  - [base]: nominal damage before the element matrix / armor pipeline.
 *    CEL-evaluable so a YAML spec can write `caster.level * 1.5`.
 *  - [attribute]: element family the band routes through.
 *  - [multiplier]: CEL-evaluable scaling applied before the matrix. Default
 *    `1.0` preserves the legacy `(base, attribute)` shape.
 */
public data class DamageComponent(
    public val base: NumericValue,
    public val attribute: DamageAttribute,
    public val multiplier: NumericValue = NumericValue.Literal(1.0),
)
