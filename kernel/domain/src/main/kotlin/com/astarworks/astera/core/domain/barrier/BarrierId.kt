package com.astarworks.astera.core.domain.barrier

/**
 * Identifier of a barrier (custom enclosure block).
 *
 * Cross-plugin value class: Combat's `DamageSource.Barrier` references it
 * for damage attribution; Match's `Barrier` aggregate (Wave 5d) owns the
 * full lifecycle (region + IBarrierGateway port + DisplayEntity visual).
 * Lives in Core so neither plugin depends on the other for a pure ID.
 */
@JvmInline
public value class BarrierId(public val value: String) {
    init {
        require(value.isNotBlank()) { "BarrierId must not be blank" }
    }
}
