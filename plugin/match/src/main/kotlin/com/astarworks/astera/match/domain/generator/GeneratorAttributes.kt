package com.astarworks.astera.match.domain.generator

import com.astarworks.astera.core.domain.attribute.AttributeKey

/**
 * Feature-local [AttributeKey] registry for Star Generators (ADR-0058 §2).
 *
 * Lives under `match.domain.generator` rather than core's
 * [com.astarworks.astera.core.domain.attribute.Attributes] so feature-specific
 * keys grow next to the domain they describe. The namespace prefix
 * `generator.*` keeps them collision-free with other plugins.
 *
 * ## Keys
 *
 * - [ENERGY] — current charge, expressed in game ticks. When this crosses the
 *   generator's effective period the scheduler emits
 *   [com.astarworks.astera.match.domain.event.ResourceSpawned] and resets the
 *   charge by subtracting the period (carries the overshoot into the next
 *   cycle).
 * - [PERIOD_MULTIPLIER] — multiplicative knob on the generator's base period.
 *   `< 1.0` accelerates (Sudden Death: MULTIPLY 0.5 → twice as fast),
 *   `> 1.0` slows. Resolved via
 *   [com.astarworks.astera.core.domain.attribute.IAttributeContainer.effectiveNumber]
 *   so multiple sources stack predictably.
 * - [AMOUNT_MULTIPLIER] — multiplicative knob on the per-spawn amount.
 *   Anomalies / boons set this to `2.0` for the "Resource Spike" burst.
 *
 * Modifiers are written via [AttributeModifier] with `sourceId` tags such as
 * `"sudden-death:<matchId>"` so they can be torn down in bulk at phase exit.
 */
public object GeneratorAttributes {
    public val ENERGY: AttributeKey = AttributeKey("generator.energy")
    public val PERIOD_MULTIPLIER: AttributeKey = AttributeKey("generator.period_multiplier")
    public val AMOUNT_MULTIPLIER: AttributeKey = AttributeKey("generator.amount_multiplier")
}
