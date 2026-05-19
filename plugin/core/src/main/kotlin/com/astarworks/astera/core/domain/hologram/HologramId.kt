package com.astarworks.astera.core.domain.hologram

import java.util.UUID

@JvmInline
public value class HologramId(public val value: UUID) {
    public companion object {
        public fun random(): HologramId = HologramId(UUID.randomUUID())
    }

    override fun toString(): String = value.toString()
}
