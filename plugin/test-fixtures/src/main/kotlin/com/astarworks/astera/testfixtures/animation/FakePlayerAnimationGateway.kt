package com.astarworks.astera.testfixtures.animation

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.animation.IPlayerAnimationGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.player.PlayerAnimationError
import com.astarworks.astera.core.domain.animation.player.PlayerHand
import com.astarworks.astera.core.domain.animation.player.SitTarget
import com.astarworks.astera.core.domain.event.PlayerEquipped
import com.astarworks.astera.core.domain.event.PlayerSitChanged
import com.astarworks.astera.core.domain.event.PlayerSleepChanged
import com.astarworks.astera.core.domain.event.PlayerSneakChanged
import com.astarworks.astera.core.domain.event.PlayerSwungArm
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IPlayerAnimationGateway] for use-case tests (Wave 5animation, ADR-0113).
 *
 * Same observable contract as [com.astarworks.astera.core.application.service.animation.InMemoryPlayerAnimationGateway]
 * but keeps an [operations] history for ordered assertion. [eventBus] is
 * nullable so tests that only assert call sequence can construct
 * `FakePlayerAnimationGateway()` with no arguments.
 */
public class FakePlayerAnimationGateway(
    private val eventBus: IDomainEventBus? = null,
    private val tickClock: () -> Long = { 0L },
) : IPlayerAnimationGateway {

    /** Operation history. Tests assert on this list to verify call sequence. */
    public sealed interface AnimationOp {
        public data class ArmSwung(val playerId: PlayerId, val hand: PlayerHand) : AnimationOp
        public data class Equipped(val playerId: PlayerId, val slot: EquipSlot, val item: ItemId) : AnimationOp
        public data class SneakSet(val playerId: PlayerId, val sneaking: Boolean) : AnimationOp
        public data class SleepSet(val playerId: PlayerId, val world: WorldId, val position: Vec3, val sleeping: Boolean) : AnimationOp
        public data class SitSet(val playerId: PlayerId, val target: SitTarget) : AnimationOp
        public data class CustomPlayed(val playerId: PlayerId, val animation: CustomAnimationId, val durationTicks: Long) : AnimationOp
    }

    private val sneakStates: ConcurrentHashMap<PlayerId, Boolean> = ConcurrentHashMap()
    private val sleepStates: ConcurrentHashMap<PlayerId, Boolean> = ConcurrentHashMap()
    private val sitStates: ConcurrentHashMap<PlayerId, SitTarget> = ConcurrentHashMap()
    private val ops: MutableList<AnimationOp> = mutableListOf()

    /** Ordered call sequence — tests assert ordering on this. */
    public val operations: List<AnimationOp> get() = ops.toList()

    override fun swingArm(
        playerId: PlayerId,
        hand: PlayerHand,
    ): Result<Unit, PlayerAnimationError> {
        ops += AnimationOp.ArmSwung(playerId, hand)
        eventBus?.publish(PlayerSwungArm(playerId, hand, tickClock()))
        return Result.success(Unit)
    }

    override fun applyEquipAnimation(
        playerId: PlayerId,
        slot: EquipSlot,
        item: ItemId,
    ): Result<Unit, PlayerAnimationError> {
        ops += AnimationOp.Equipped(playerId, slot, item)
        eventBus?.publish(PlayerEquipped(playerId, slot, item, tickClock()))
        return Result.success(Unit)
    }

    override fun setSneaking(
        playerId: PlayerId,
        sneaking: Boolean,
    ): Result<Unit, PlayerAnimationError> {
        val previous = sneakStates[playerId]
        if (previous == sneaking) {
            return Result.success(Unit)
        }
        sneakStates[playerId] = sneaking
        ops += AnimationOp.SneakSet(playerId, sneaking)
        eventBus?.publish(PlayerSneakChanged(playerId, sneaking, tickClock()))
        return Result.success(Unit)
    }

    override fun setSleeping(
        playerId: PlayerId,
        world: WorldId,
        position: Vec3,
        sleeping: Boolean,
    ): Result<Unit, PlayerAnimationError> {
        val previous = sleepStates[playerId]
        if (previous == sleeping) {
            return Result.success(Unit)
        }
        sleepStates[playerId] = sleeping
        ops += AnimationOp.SleepSet(playerId, world, position, sleeping)
        eventBus?.publish(PlayerSleepChanged(playerId, world, position, sleeping, tickClock()))
        return Result.success(Unit)
    }

    override fun setSitting(
        playerId: PlayerId,
        target: SitTarget,
    ): Result<Unit, PlayerAnimationError> {
        val previous = sitStates[playerId] ?: SitTarget.None
        if (previous == target) {
            return Result.success(Unit)
        }
        if (target is SitTarget.None) {
            sitStates.remove(playerId)
        } else {
            sitStates[playerId] = target
        }
        ops += AnimationOp.SitSet(playerId, target)
        eventBus?.publish(PlayerSitChanged(playerId, target, tickClock()))
        return Result.success(Unit)
    }

    override fun playCustomAnimation(
        playerId: PlayerId,
        animation: CustomAnimationId,
        durationTicks: Long,
    ): Result<Unit, PlayerAnimationError> {
        if (durationTicks < 0L) {
            return Result.failure(
                PlayerAnimationError.InvalidDuration(
                    "playCustomAnimation.durationTicks must be >= 0 (got $durationTicks)",
                ),
            )
        }
        ops += AnimationOp.CustomPlayed(playerId, animation, durationTicks)
        return Result.success(Unit)
    }

    override fun sneakState(playerId: PlayerId): Boolean? = sneakStates[playerId]

    override fun sitState(playerId: PlayerId): SitTarget = sitStates[playerId] ?: SitTarget.None

    /** Test cleanup — drops every op + tracked state. */
    public fun clear() {
        ops.clear()
        sneakStates.clear()
        sleepStates.clear()
        sitStates.clear()
    }
}
