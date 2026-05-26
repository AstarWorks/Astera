package com.astarworks.astera.core.application.service.animation

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
 * Default in-memory [IPlayerAnimationGateway] (Wave 5animation, ADR-0113).
 *
 * Wired by `AsteraCorePlugin` at priority 0 — Wave 6 lands
 * `PaperPlayerAnimationGateway` over Bukkit `Player.swingMainHand()` /
 * `Player.swingOffHand()` / `Player.setSneaking()` / `Player.sleep()` etc.
 * at higher priority and shadows this impl.
 *
 * **Not thread-safe** beyond what [ConcurrentHashMap] provides. The
 * Paper adapter pipeline runs on the main thread; the concurrent map is
 * precautionary for cross-thread skill-engine callers.
 *
 * The gateway publishes events through the constructor-injected
 * [eventBus] so tests can pass a fake bus and assert event ordering.
 *
 * The [tickClock] is a lambda so tests can stub a deterministic tick
 * (production wiring passes the Paper server tick supplier).
 */
public class InMemoryPlayerAnimationGateway(
    private val eventBus: IDomainEventBus,
    private val tickClock: () -> Long = { 0L },
) : IPlayerAnimationGateway {

    private val sneakStates: ConcurrentHashMap<PlayerId, Boolean> = ConcurrentHashMap()
    private val sleepStates: ConcurrentHashMap<PlayerId, Boolean> = ConcurrentHashMap()
    private val sitStates: ConcurrentHashMap<PlayerId, SitTarget> = ConcurrentHashMap()

    override fun swingArm(
        playerId: PlayerId,
        hand: PlayerHand,
    ): Result<Unit, PlayerAnimationError> {
        eventBus.publish(
            PlayerSwungArm(
                playerId = playerId,
                hand = hand,
                atTick = tickClock(),
            ),
        )
        return Result.success(Unit)
    }

    override fun applyEquipAnimation(
        playerId: PlayerId,
        slot: EquipSlot,
        item: ItemId,
    ): Result<Unit, PlayerAnimationError> {
        eventBus.publish(
            PlayerEquipped(
                playerId = playerId,
                slot = slot,
                item = item,
                atTick = tickClock(),
            ),
        )
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
        eventBus.publish(
            PlayerSneakChanged(
                playerId = playerId,
                sneaking = sneaking,
                atTick = tickClock(),
            ),
        )
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
        eventBus.publish(
            PlayerSleepChanged(
                playerId = playerId,
                world = world,
                position = position,
                sleeping = sleeping,
                atTick = tickClock(),
            ),
        )
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
        eventBus.publish(
            PlayerSitChanged(
                playerId = playerId,
                target = target,
                atTick = tickClock(),
            ),
        )
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
        // Phase 1 impl: accept any id and succeed. The Phase 4 cosmetic
        // emote registry will validate at the call site before invoking.
        return Result.success(Unit)
    }

    override fun sneakState(playerId: PlayerId): Boolean? = sneakStates[playerId]

    override fun sitState(playerId: PlayerId): SitTarget = sitStates[playerId] ?: SitTarget.None
}
