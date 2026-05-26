package com.astarworks.astera.testfixtures.animation

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
 * In-memory [IEntityAnimationGateway] for use-case tests (Wave 5animation, ADR-0113).
 *
 * Records every call and publishes the matching event when an [eventBus]
 * is supplied. Mirrors [com.astarworks.astera.core.application.service.animation.InMemoryEntityAnimationGateway]
 * but exposes an [operations] history for ordered assertion.
 */
public class FakeEntityAnimationGateway(
    private val eventBus: IDomainEventBus? = null,
    private val tickClock: () -> Long = { 0L },
) : IEntityAnimationGateway {

    /** Operation history. Tests assert on this list. */
    public sealed interface MobAnimOp {
        public data class Attack(val handle: EntityHandle) : MobAnimOp
        public data class Death(val handle: EntityHandle) : MobAnimOp
        public data class Pickup(val handle: EntityHandle, val item: ItemId) : MobAnimOp
        public data class DamageFlash(val handle: EntityHandle) : MobAnimOp
        public data class Drown(val handle: EntityHandle, val drowning: Boolean) : MobAnimOp
        public data class LookAt(val handle: EntityHandle, val direction: Vec3) : MobAnimOp
    }

    private val ops: MutableList<MobAnimOp> = mutableListOf()

    /** Ordered call sequence. */
    public val operations: List<MobAnimOp> get() = ops.toList()

    override fun playMobAttackAnimation(
        handle: EntityHandle,
    ): Result<Unit, EntityAnimationError> {
        ops += MobAnimOp.Attack(handle)
        eventBus?.publish(MobAnimationPlayed(handle, MobAnimationKind.Attack, tickClock()))
        return Result.success(Unit)
    }

    override fun playMobDeathAnimation(
        handle: EntityHandle,
    ): Result<Unit, EntityAnimationError> {
        ops += MobAnimOp.Death(handle)
        eventBus?.publish(MobAnimationPlayed(handle, MobAnimationKind.Death, tickClock()))
        return Result.success(Unit)
    }

    override fun playMobPickupAnimation(
        handle: EntityHandle,
        item: ItemId,
    ): Result<Unit, EntityAnimationError> {
        ops += MobAnimOp.Pickup(handle, item)
        eventBus?.publish(MobAnimationPlayed(handle, MobAnimationKind.Pickup(item), tickClock()))
        return Result.success(Unit)
    }

    override fun playMobDamageFlash(
        handle: EntityHandle,
    ): Result<Unit, EntityAnimationError> {
        ops += MobAnimOp.DamageFlash(handle)
        eventBus?.publish(MobAnimationPlayed(handle, MobAnimationKind.DamageFlash, tickClock()))
        return Result.success(Unit)
    }

    override fun playMobDrownAnimation(
        handle: EntityHandle,
        drowning: Boolean,
    ): Result<Unit, EntityAnimationError> {
        ops += MobAnimOp.Drown(handle, drowning)
        eventBus?.publish(MobAnimationPlayed(handle, MobAnimationKind.Drown(drowning), tickClock()))
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
        val normalised = direction.normalize()
        ops += MobAnimOp.LookAt(handle, normalised)
        eventBus?.publish(MobAnimationPlayed(handle, MobAnimationKind.LookAt(normalised), tickClock()))
        return Result.success(Unit)
    }

    /** Test cleanup — drops every recorded op. */
    public fun clear() {
        ops.clear()
    }
}
