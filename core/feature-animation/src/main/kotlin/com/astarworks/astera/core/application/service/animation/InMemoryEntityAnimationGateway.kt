package com.astarworks.astera.core.application.service.animation

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.animation.IEntityAnimationGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.entity.EntityAnimationError
import com.astarworks.astera.core.domain.animation.entity.MobAnimationKind
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.MobAnimationPlayed
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Default in-memory [IEntityAnimationGateway] (Wave 5animation, ADR-0113).
 *
 * Wired by `AsteraCorePlugin` at priority 0 — Wave 6 lands
 * `PaperEntityAnimationGateway` over Bukkit `LivingEntity.swingMainHand()`
 * + packet primitives at higher priority.
 *
 * The gateway intentionally does **not** validate that [EntityHandle] is
 * "alive" — handle liveness is the entity-spawner's concern, and the
 * Wave 6 adapter ignores stale handles silently anyway. The in-memory
 * impl matches this behaviour to avoid divergent test outcomes.
 *
 * # Why direction validation lives here
 *
 * [setMobLookDirection] is the only operation with a domain invariant
 * (non-zero finite vector). Putting the check in the gateway (vs the
 * caller) means every caller benefits without duplicating the math.
 */
public class InMemoryEntityAnimationGateway(
    private val eventBus: IDomainEventBus,
    private val tickClock: () -> Long = { 0L },
) : IEntityAnimationGateway {

    override fun playMobAttackAnimation(
        handle: EntityHandle,
    ): Result<Unit, EntityAnimationError> {
        eventBus.publish(
            MobAnimationPlayed(
                handle = handle,
                kind = MobAnimationKind.Attack,
                atTick = tickClock(),
            ),
        )
        return Result.success(Unit)
    }

    override fun playMobDeathAnimation(
        handle: EntityHandle,
    ): Result<Unit, EntityAnimationError> {
        eventBus.publish(
            MobAnimationPlayed(
                handle = handle,
                kind = MobAnimationKind.Death,
                atTick = tickClock(),
            ),
        )
        return Result.success(Unit)
    }

    override fun playMobPickupAnimation(
        handle: EntityHandle,
        item: ItemId,
    ): Result<Unit, EntityAnimationError> {
        eventBus.publish(
            MobAnimationPlayed(
                handle = handle,
                kind = MobAnimationKind.Pickup(item),
                atTick = tickClock(),
            ),
        )
        return Result.success(Unit)
    }

    override fun playMobDamageFlash(
        handle: EntityHandle,
    ): Result<Unit, EntityAnimationError> {
        eventBus.publish(
            MobAnimationPlayed(
                handle = handle,
                kind = MobAnimationKind.DamageFlash,
                atTick = tickClock(),
            ),
        )
        return Result.success(Unit)
    }

    override fun playMobDrownAnimation(
        handle: EntityHandle,
        drowning: Boolean,
    ): Result<Unit, EntityAnimationError> {
        eventBus.publish(
            MobAnimationPlayed(
                handle = handle,
                kind = MobAnimationKind.Drown(drowning),
                atTick = tickClock(),
            ),
        )
        return Result.success(Unit)
    }

    override fun setMobLookDirection(
        handle: EntityHandle,
        direction: Vec3,
    ): Result<Unit, EntityAnimationError> {
        val l2 = direction.lengthSquared()
        if (l2 == 0.0) {
            return Result.failure(
                EntityAnimationError.InvalidDirection(
                    "setMobLookDirection.direction must be non-zero (got ZERO)",
                ),
            )
        }
        if (!l2.isFinite()) {
            return Result.failure(
                EntityAnimationError.InvalidDirection(
                    "setMobLookDirection.direction must be finite (got $direction)",
                ),
            )
        }
        eventBus.publish(
            MobAnimationPlayed(
                handle = handle,
                kind = MobAnimationKind.LookAt(direction.normalize()),
                atTick = tickClock(),
            ),
        )
        return Result.success(Unit)
    }
}
