package com.astarworks.astera.combat.domain.armor

/** Stable identifier of an [ArmorSet]. */
@JvmInline
public value class ArmorSetKey(public val value: String) {
    init {
        require(value.isNotBlank()) { "ArmorSetKey must not be blank" }
    }
}
