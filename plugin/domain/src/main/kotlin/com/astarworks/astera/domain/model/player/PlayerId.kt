package com.astarworks.astera.domain.model.player

import java.util.UUID

/**
 * Domain-level player identifier. Wraps a UUID and is intentionally distinct
 * from any platform's Player type (Bukkit / Velocity / Discord). Crossing the
 * platform boundary is the adapter layer's responsibility.
 */
@JvmInline
value class PlayerId(val uuid: UUID) {
    override fun toString(): String = uuid.toString()

    companion object {
        fun parse(s: String): PlayerId = PlayerId(UUID.fromString(s))
    }
}
