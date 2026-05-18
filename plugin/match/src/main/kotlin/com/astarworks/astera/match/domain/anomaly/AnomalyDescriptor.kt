package com.astarworks.astera.match.domain.anomaly

import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeModifier

/**
 * Pre-registered shape for one anomaly variant assigned to a match (Wave 5c).
 *
 * Descriptors are populated at match start (typically from
 * `content/anomaly/<file>.yaml`) and stored in
 * [com.astarworks.astera.match.application.port.outbound.IAnomalyScheduleRepository].
 * The scheduler then samples them weighted-randomly per tick and turns each
 * fire into a real
 * [com.astarworks.astera.core.domain.attribute.AttributeModifier] applied via
 * `ResolveAnomalyEffectUseCase`.
 *
 * @property type The anomaly flavor (also carries the i18n display key).
 * @property weight Relative likelihood used by weighted random sampling.
 *   Higher = more frequent. Must be `>= 1`.
 * @property durationTicks How long the anomaly's modifier stays applied
 *   before the scheduler removes it. Tick-based so it composes with
 *   [com.astarworks.astera.core.application.port.outbound.IScheduler] without
 *   `Duration` round-trips at every callsite.
 * @property attributeKey Which [AttributeKey] the resulting modifier targets.
 *   For [AnomalyType.ResourceSpike] this is
 *   [com.astarworks.astera.match.domain.generator.GeneratorAttributes.PERIOD_MULTIPLIER].
 *   Other anomaly types currently target their own keys in
 *   [AnomalyAttributes] (the live wiring lands in Wave 6).
 * @property modifierOperation [AttributeModifier.Operation] (`ADD` /
 *   `MULTIPLY` / `SET`). For multiplicative tempo changes (the Phase 1 PoC
 *   case) this is `MULTIPLY`.
 * @property modifierValue Numeric payload paired with `modifierOperation`.
 */
public data class AnomalyDescriptor(
    val type: AnomalyType,
    val weight: Int,
    val durationTicks: Long,
    val attributeKey: AttributeKey,
    val modifierOperation: AttributeModifier.Operation,
    val modifierValue: Double,
) {
    init {
        require(weight >= 1) { "AnomalyDescriptor.weight must be >= 1 (was $weight)" }
        require(durationTicks > 0L) { "AnomalyDescriptor.durationTicks must be > 0 (was $durationTicks)" }
    }
}
