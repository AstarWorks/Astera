package com.astarworks.astera.domain.model.projectile

import java.util.UUID

/**
 * Identifier for a live projectile instance.
 *
 * UUIDs are used rather than monotonic ints to avoid coordination across
 * server cores and to make persistence cheap if a future Phase ever wants
 * to track projectile history.
 */
@JvmInline
public value class ProjectileId(public val uuid: UUID) {
    override fun toString(): String = uuid.toString()

    public companion object {
        public fun random(): ProjectileId = ProjectileId(UUID.randomUUID())
        public fun parse(s: String): ProjectileId = ProjectileId(UUID.fromString(s))
    }
}
