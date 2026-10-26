package com.astarworks.astera.core.application.port.outbound.animation

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.entity.EntityAnimationError
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Outbound port: trigger entity-side animations (Wave 5animation, ADR-0113).
 *
 * Companion to [IPlayerAnimationGateway] but scoped to mobs / NPCs /
 * display entities. Wave 6 lands `PaperEntityAnimationGateway` over
 * Bukkit `LivingEntity.swingMainHand()` / `Player.playEffect()` +
 * raw packet variants where Bukkit lacks coverage (vanilla pickup
 * animation requires `CollectItemPacket`).
 *
 * Phase 1 PoC ships `InMemoryEntityAnimationGateway` (records calls,
 * publishes [com.astarworks.astera.core.domain.event.MobAnimationPlayed]).
 *
 * # Vendor-neutral by construction
 *
 * No type in this interface imports `org.bukkit.*` / `io.papermc.*`.
 * [EntityHandle] is the vendor-neutral lookup key minted by the entity
 * spawner.
 */
public interface IEntityAnimationGateway {

    /**
     * Trigger the vanilla mob attack swing animation for [handle].
     * Publishes [com.astarworks.astera.core.domain.event.MobAnimationPlayed] with
     * [com.astarworks.astera.core.domain.animation.entity.MobAnimationKind.Attack].
     */
    public fun playMobAttackAnimation(
        handle: EntityHandle,
    ): Result<Unit, EntityAnimationError>

    /**
     * Trigger the vanilla mob death wobble animation. The despawn itself
     * is **not** scheduled here — callers compose
     * `IScheduler.run(20.ticks) { mobSpawner.despawn(handle) }`
     * to delay despawn so the animation completes.
     */
    public fun playMobDeathAnimation(
        handle: EntityHandle,
    ): Result<Unit, EntityAnimationError>

    /**
     * Trigger the vanilla mob pickup animation (zombie picking up sword).
     * [item] identifies what was picked up so subscribers can react.
     */
    public fun playMobPickupAnimation(
        handle: EntityHandle,
        item: ItemId,
    ): Result<Unit, EntityAnimationError>

    /**
     * Trigger the damage red-flash animation. Idempotent within the same
     * tick (multiple calls in the same tick coalesce in the Paper adapter
     * to avoid packet spam; the in-memory impl publishes one event per
     * call).
     */
    public fun playMobDamageFlash(
        handle: EntityHandle,
    ): Result<Unit, EntityAnimationError>

    /**
     * Toggle the drowning pose.
     *
     * @param drowning `true` = entered drowning state, `false` = recovered.
     */
    public fun playMobDrownAnimation(
        handle: EntityHandle,
        drowning: Boolean,
    ): Result<Unit, EntityAnimationError>

    /**
     * Update the mob's look direction. [direction] must be a non-zero
     * vector; the gateway normalises before applying. Returns
     * [EntityAnimationError.InvalidDirection] if [direction] is the zero
     * vector or non-finite.
     */
    public fun setMobLookDirection(
        handle: EntityHandle,
        direction: Vec3,
    ): Result<Unit, EntityAnimationError>
}
