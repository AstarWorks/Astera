package com.astarworks.astera.combat.domain.damage

/**
 * Identifier of a barrier (custom enclosure block).
 *
 * Wave 5a declares this stub so [DamageSource.Barrier] can compile. Wave 5d
 * (world geometry) introduces the full Barrier domain (region + DisplayEntity
 * visual + IBarrierGateway port).
 */
@JvmInline
public value class BarrierId(public val value: String) {
    init {
        require(value.isNotBlank()) { "BarrierId must not be blank" }
    }
}
