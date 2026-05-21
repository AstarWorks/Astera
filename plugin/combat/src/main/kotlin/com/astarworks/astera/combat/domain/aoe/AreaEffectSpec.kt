package com.astarworks.astera.combat.domain.aoe

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Descriptor for a persistent area-effect zone (Wave 5m / ADR-0088).
 *
 * The gateway schedules [effectPerTick] every [tickIntervalTicks] ticks
 * for at most [durationTicks] ticks, then auto-despawns. Callers receive
 * an [AreaEffectHandle] that lets them despawn early.
 *
 * @property center World-space anchor of the shape.
 * @property world Target world; gateway returns
 *   [AreaEffectError.WorldNotLoaded] if unloaded at spawn time.
 * @property shape One of [AreaEffectShape.Circle] / [Box] / [Ring].
 * @property durationTicks Lifetime in server ticks. `0` means
 *   "fire once and despawn" — useful for one-shot AOEs that still want
 *   the zone abstraction (e.g. analytics scope).
 * @property tickIntervalTicks Cadence of [effectPerTick]. Must be `>= 1`;
 *   the gateway clamps to at least 1 to avoid tight-loop scheduling.
 * @property effectPerTick The per-tick action. See [AreaEffectAction].
 */
public data class AreaEffectSpec(
    public val center: Vec3,
    public val world: WorldId,
    public val shape: AreaEffectShape,
    public val durationTicks: Int,
    public val tickIntervalTicks: Int,
    public val effectPerTick: AreaEffectAction,
) {
    init {
        require(durationTicks >= 0) {
            "AreaEffectSpec.durationTicks must be >= 0: $durationTicks"
        }
        require(tickIntervalTicks >= 1) {
            "AreaEffectSpec.tickIntervalTicks must be >= 1: $tickIntervalTicks"
        }
    }
}

/**
 * Opaque handle returned by
 * [com.astarworks.astera.combat.application.port.outbound.aoe.IAreaEffectGateway.spawn].
 *
 * [backendId] mirrors the [com.astarworks.astera.core.domain.entity.EntityHandle]
 * pattern from ADR-0082: every gateway impl tags its handles so a
 * cross-impl despawn cannot accidentally collide.
 */
public data class AreaEffectHandle(
    public val id: java.util.UUID,
    public val backendId: String,
)

/** Failure modes for `IAreaEffectGateway`. */
public sealed interface AreaEffectError {

    /** Target world is not loaded. */
    public data class WorldNotLoaded(public val world: WorldId) : AreaEffectError

    /** The handle was never registered with this gateway (or already despawned). */
    public data class UnknownHandle(public val handle: AreaEffectHandle) : AreaEffectError

    /** Backend impl is not currently available — only the placeholder reports this. */
    public data object NoImplementationBound : AreaEffectError
}
