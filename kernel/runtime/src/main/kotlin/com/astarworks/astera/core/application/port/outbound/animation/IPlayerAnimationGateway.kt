package com.astarworks.astera.core.application.port.outbound.animation

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.player.PlayerAnimationError
import com.astarworks.astera.core.domain.animation.player.PlayerHand
import com.astarworks.astera.core.domain.animation.player.SitTarget
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: trigger player-side animations (Wave 5animation, ADR-0113).
 *
 * Phase 1 PoC ships `InMemoryPlayerAnimationGateway` + `FakePlayerAnimationGateway`.
 * Wave 6 lands `PaperPlayerAnimationGateway` over Bukkit
 * `Player.swingMainHand()` / `Player.swingOffHand()` / `Player.setSneaking()` /
 * `Player.sleep()` / `Player.wakeup()` + per-method packet variants where
 * the Bukkit API is too coarse (e.g. equip animation triggers an
 * `EntityEquipmentPacket` directly).
 *
 * # Why a single gateway (not 5 separate ports)
 *
 * Player animation operations all share the same observable contract
 * (event publish on success, no return state needed beyond ack), and Wave 6
 * will wire them all to the same Bukkit-side adapter. A single port keeps
 * `AsteraCorePlugin` wiring lean — one `services.register` line.
 *
 * # Vendor-neutral by construction
 *
 * No type in this interface imports `org.bukkit.*` / `io.papermc.*` /
 * `com.mojang.*`. The adapter (Wave 6) does the conversion at the edge.
 *
 * # Idempotency contract
 *
 * - `setSneaking(playerId, sneaking)` is idempotent — re-asserting the
 *   current state returns Success but does not publish an event.
 * - `setSleeping(...)` follows the same rule.
 * - `setSitting(playerId, SitTarget.None)` on a player who isn't sitting
 *   returns Success and does not publish [com.astarworks.astera.core.domain.event.PlayerSitChanged].
 * - `swingArm` / `applyEquipAnimation` / `playCustomAnimation` always
 *   publish on success — they are one-shot triggers, not state mutations.
 */
public interface IPlayerAnimationGateway {

    /**
     * Swing the player's [hand]. Triggers the vanilla swing animation
     * for every viewer in render distance, then publishes
     * [com.astarworks.astera.core.domain.event.PlayerSwungArm].
     */
    public fun swingArm(
        playerId: PlayerId,
        hand: PlayerHand,
    ): Result<Unit, PlayerAnimationError>

    /**
     * Play the equip animation for [item] in [slot] — typically used when
     * a use case grants the player a new piece of gear and wants the
     * vanilla "pop-in" animation to fire. The adapter is responsible for
     * any inventory-side write (this port is animation-only); the use
     * case must update inventory state separately if the player did not
     * already have the item.
     *
     * Publishes [com.astarworks.astera.core.domain.event.PlayerEquipped] on success.
     */
    public fun applyEquipAnimation(
        playerId: PlayerId,
        slot: EquipSlot,
        item: ItemId,
    ): Result<Unit, PlayerAnimationError>

    /**
     * Set the player's sneak state. Idempotent: re-asserting the current
     * state returns Success without publishing an event. On a real state
     * change, publishes [com.astarworks.astera.core.domain.event.PlayerSneakChanged].
     */
    public fun setSneaking(
        playerId: PlayerId,
        sneaking: Boolean,
    ): Result<Unit, PlayerAnimationError>

    /**
     * Set the player's sleep state. When [sleeping] = true, [position]
     * is the bed position; when false, it's the wake-up stand position.
     * The adapter is responsible for any world-side write (bed
     * occupation). Publishes [com.astarworks.astera.core.domain.event.PlayerSleepChanged]
     * on a real state change.
     */
    public fun setSleeping(
        playerId: PlayerId,
        world: WorldId,
        position: Vec3,
        sleeping: Boolean,
    ): Result<Unit, PlayerAnimationError>

    /**
     * Set the player's sit target.
     *
     * - `target = SitTarget.Vehicle(...)` mounts on the entity.
     * - `target = SitTarget.Position(...)` synthesises an invisible
     *   armor-stand at the position and seats the player.
     * - `target = SitTarget.None` dismounts and clears any synthesised
     *   armor-stand.
     *
     * Publishes [com.astarworks.astera.core.domain.event.PlayerSitChanged] on a
     * real change.
     */
    public fun setSitting(
        playerId: PlayerId,
        target: SitTarget,
    ): Result<Unit, PlayerAnimationError>

    /**
     * Play a custom registered animation for [durationTicks] ticks.
     * Returns [PlayerAnimationError.UnknownCustomAnimation] if the id
     * isn't registered. Phase 1 in-memory impl validates the id only
     * (the registry-side lookup is owned by the caller in Phase 1; the
     * gateway accepts any id and returns Success). Wave 6 + the cosmetic
     * emote system fully wire the resolution.
     */
    public fun playCustomAnimation(
        playerId: PlayerId,
        animation: CustomAnimationId,
        durationTicks: Long,
    ): Result<Unit, PlayerAnimationError>

    /**
     * Snapshot of the player's tracked sneak state, or `null` if the
     * gateway has no record (player never had `setSneaking` called).
     *
     * Used by tests to assert state without going through the bus.
     */
    public fun sneakState(playerId: PlayerId): Boolean?

    /**
     * Snapshot of the player's tracked sit state, or `SitTarget.None` if
     * the player is standing (or the gateway has no record).
     */
    public fun sitState(playerId: PlayerId): SitTarget
}
