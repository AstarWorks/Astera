package com.astarworks.astera.core.domain.animation.entity

import com.astarworks.astera.core.domain.entity.EntityHandle

/**
 * Failure modes of [com.astarworks.astera.core.application.port.outbound.animation.IEntityAnimationGateway]
 * (Wave 5animation, ADR-0113).
 *
 * Sealed for the same reason as [com.astarworks.astera.core.domain.animation.player.PlayerAnimationError] —
 * keeps the application API surface free of raw JVM throwables (Konsist
 * rule "application layers must not import raw JVM throwable types").
 */
public sealed class EntityAnimationError {

    /**
     * The supplied entity handle is no longer tracked by the gateway
     * (despawned / never spawned via the entity spawner). Idempotent
     * operations that can safely no-op (e.g. damage flash on an entity
     * that just died one tick ago) should return Success; this variant
     * is reserved for cases where the caller needs to know the entity is
     * gone.
     */
    public data class UnknownEntity(public val handle: EntityHandle) : EntityAnimationError()

    /** Look-direction vector was zero-length or non-finite. */
    public data class InvalidDirection(public val detail: String) : EntityAnimationError()

    /** Generic platform failure (Bukkit-side exception in the Paper adapter). */
    public data class Platform(public val detail: String) : EntityAnimationError()
}
