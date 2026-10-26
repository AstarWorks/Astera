package com.astarworks.astera.core.domain.world

/**
 * Vendor-neutral identifier for a world (Bukkit's `World.name`, but stripped
 * of platform coupling). Used by [com.astarworks.astera.core.application.port.outbound.IWorldGateway]
 * and similar ports.
 */
@JvmInline
public value class WorldId(public val value: String) {
    init {
        require(value.isNotBlank()) { "WorldId must not be blank" }
    }

    override fun toString(): String = value
}
