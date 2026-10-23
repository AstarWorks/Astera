package com.astarworks.astera.core.adapter.paper.animation

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
import org.bukkit.Material
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper concrete of [IPlayerAnimationGateway] (Wave 6d, ADR-0161). Bukkit
 * `Player` animation API (`swingMainHand` / `swingOffHand` / `setSneaking` /
 * `sleep` / `wakeup` / vehicle-mount) plus state-tracking idempotency
 * **mirror** of
 * [com.astarworks.astera.core.application.service.animation.InMemoryPlayerAnimationGateway].
 *
 * # Idempotency mirror
 *
 * The InMemory SSOT keeps three `ConcurrentHashMap`s (sneak / sleep / sit
 * states) and only publishes domain events when the requested state differs
 * from the tracked prior. This adapter replicates the same maps + the same
 * comparison so the Paper and in-memory paths emit the **same event stream**
 * for the same call sequence. The Bukkit-side `Player.isSneaking` flag is
 * authoritative for the actual MC client, but the tracked state is the
 * authority for "should we publish an event now". The two can briefly
 * diverge if another plugin toggles sneak directly on the Player object —
 * that's tolerated; the next `setSneaking` call from a use case re-aligns.
 *
 * # Bukkit mapping
 *
 *  - [swingArm] → `player.swingMainHand()` / `player.swingOffHand()` per hand.
 *  - [setSneaking] → `player.isSneaking = sneaking` after tracked-state diff.
 *  - [setSleeping] → `player.sleep(loc, /* force = */ true)` when sleeping;
 *    `player.wakeup(/* setSpawnLocation = */ false)` when waking. The "true"
 *    force flag bypasses Bukkit's "must be a valid bed" check so use cases
 *    can lay players down on arbitrary positions during cinematics.
 *  - [setSitting] →
 *      - `Vehicle(handle)`: `server.getEntity(handle.id).addPassenger(player)`.
 *      - `Position(vec3)`: spawn an invisible/marker/no-gravity [ArmorStand]
 *        at the position and mount the player on it. The synthetic stand's
 *        UUID is tracked so [setSitting] with `None` (or with a different
 *        target replacing a previous `Position`) can `entity.remove()` it.
 *      - `None`: `player.leaveVehicle()` + remove any tracked synthetic
 *        armor stand for this player.
 *  - [applyEquipAnimation] → **animation-only**: this adapter publishes the
 *    [PlayerEquipped] event matching the InMemory SSOT but does **not**
 *    write `player.equipment.setItem(...)`. The port KDoc states "this port
 *    is animation-only; the use case must update inventory state
 *    separately" — material lookup needs `IItemRegistry`, which Wave 6d
 *    deliberately keeps out of the animation gateway surface.
 *  - [playCustomAnimation] → `crouch` temporarily fixes the visual pose, then restores
 *    the prior pose/fixed state. Unknown animation ids fail. This is not joint animation.
 *
 * # ADR-0149 §C invariant
 *
 * N/A — this gateway never touches player HP, damage, or scoreboard. It
 * only mutates pose / vehicle / sneak / sleep state.
 *
 * # Main-thread requirement
 *
 * Every Bukkit `Player` setter touched here (swing, isSneaking, sleep,
 * wakeup, addPassenger, leaveVehicle, world.spawn) is documented main-
 * thread-only on Paper / Folia (ADR-0144 / 0145 / 0146 / 0147 / 0148).
 * Because [IPlayerAnimationGateway] returns `Result<Unit,
 * PlayerAnimationError>`, off-main calls are surfaced as
 * [PlayerAnimationError.Platform] with `detail = "off-main"` rather than
 * silently dropped. Callers from off-main paths MUST round-trip through
 * `IScheduler.runLater(Duration.ZERO, ...)`.
 *
 * # Synthetic armor-stand lifecycle
 *
 * For [SitTarget.Position] the gateway spawns an `ArmorStand` and tracks
 * its UUID in [sitStands]. The stand is removed when:
 *  - the same player is sat with [SitTarget.None] (explicit dismount),
 *  - the same player is sat with a different target (Vehicle or another
 *    Position) — the previous stand is removed before the new mount,
 *  - external code despawns it (covered by `server.getEntity` returning
 *    `null` on the next lookup; the tracking map then leaks one UUID
 *    until the next `setSitting` call clears it).
 *
 * No `onPlayerQuit` listener is wired here — that lifecycle hook can be
 * added in a follow-up wave if Phase 1 PoC sees orphaned stands. The
 * `addPassenger` ↔ `leaveVehicle` link means a dismounted stand is
 * already invisible+marker, so a brief orphan is visually a no-op.
 *
 * # Constructor
 *
 * Matches [InMemoryPlayerAnimationGateway] for parity: an [IDomainEventBus]
 * for publication and a [tickClock] lambda so tests can stub a deterministic
 * tick. Production wiring in `AsteraCorePlugin.onEnable` passes
 * `IScheduler::currentTick` as the clock.
 *
 * # Registration
 *
 * Priority 100 in `AsteraCorePlugin.onEnable` (overlays
 * [InMemoryPlayerAnimationGateway] priority 0).
 */
public class PaperPlayerAnimationGateway(
    private val server: Server,
    private val eventBus: IDomainEventBus,
    private val tickClock: () -> Long = { 0L },
    private val scheduler: com.astarworks.astera.core.application.port.outbound.IScheduler? = null,
) : IPlayerAnimationGateway {

    private val log = LoggerFactory.getLogger(javaClass)
    private data class PoseOverride(val player: Player, val previous: org.bukkit.entity.Pose, val fixed: Boolean)
    private val poseOverrides = mutableMapOf<PlayerId, PoseOverride>()
    private class HandOverride(val player: Player, var until: Long) {
        var task: com.astarworks.astera.core.application.port.outbound.IScheduler.TaskHandle? = null
    }
    private val handOverrides = mutableMapOf<PlayerId, HandOverride>()

    private fun showHand(player: Player, item: ItemStack) {
        // Appearance packets only. Never replace inventory contents or restore an old item snapshot.
        player.world.players.forEach { viewer -> viewer.sendEquipmentChange(player, EquipmentSlot.HAND, item) }
    }

    private fun releaseHand(id: PlayerId, state: HandOverride) {
        if (handOverrides[id] !== state) return
        handOverrides.remove(id)
        state.task?.cancel()
        if (onlinePlayer(id) === state.player) showHand(state.player, state.player.inventory.itemInMainHand)
    }

    private fun hideHand(id: PlayerId, player: Player, duration: Long): Result<Unit, PlayerAnimationError> {
        if (duration == 0L) return Result.success(Unit)
        val clock = scheduler ?: return Result.failure(PlayerAnimationError.Platform("Animation scheduler unavailable"))
        val until = clock.currentTick() + duration
        handOverrides[id]?.let { old ->
            if (old.player === player) {
                old.until = maxOf(old.until, until)
                return Result.success(Unit)
            }
            releaseHand(id, old)
        }
        val state = HandOverride(player, until)
        handOverrides[id] = state
        val empty = ItemStack(Material.AIR)
        showHand(player, empty)
        state.task = clock.runRepeating(
            com.astarworks.astera.core.application.port.outbound.ticks(1),
            com.astarworks.astera.core.application.port.outbound.ticks(1),
        ) {
            if (!player.isOnline || player.isDead || clock.currentTick() >= state.until) releaseHand(id, state)
            else showHand(player, empty)
        }
        return Result.success(Unit)
    }


    private fun releasePose(id: PlayerId, state: PoseOverride) {
        if (poseOverrides[id] !== state) return
        poseOverrides.remove(id)
        val player = onlinePlayer(id) ?: return
        if (player !== state.player) return
        if (player.hasFixedPose() && player.pose == org.bukkit.entity.Pose.SNEAKING) {
            player.setPose(state.previous, state.fixed)
        }
    }

    /** Release visual pose overrides before the scheduler stops on plugin disable. */
    public fun shutdown() {
        poseOverrides.toMap().forEach { (id, state) -> releasePose(id, state) }
        handOverrides.toMap().forEach { (id, state) -> releaseHand(id, state) }
    }

    private val sneakStates: ConcurrentHashMap<PlayerId, Boolean> = ConcurrentHashMap()
    private val sleepStates: ConcurrentHashMap<PlayerId, Boolean> = ConcurrentHashMap()
    private val sitStates: ConcurrentHashMap<PlayerId, SitTarget> = ConcurrentHashMap()

    /** Synthetic armor-stand UUID per player for [SitTarget.Position]. */
    private val sitStands: ConcurrentHashMap<PlayerId, UUID> = ConcurrentHashMap()

    override fun swingArm(
        playerId: PlayerId,
        hand: PlayerHand,
    ): Result<Unit, PlayerAnimationError> {
        if (!server.isPrimaryThread) return offMain("swingArm")
        val player = onlinePlayer(playerId) ?: return unknownPlayer(playerId)
        when (hand) {
            PlayerHand.MainHand -> player.swingMainHand()
            PlayerHand.OffHand -> player.swingOffHand()
        }
        eventBus.publish(PlayerSwungArm(playerId = playerId, hand = hand, atTick = tickClock()))
        return Result.success(Unit)
    }

    override fun applyEquipAnimation(
        playerId: PlayerId,
        slot: EquipSlot,
        item: ItemId,
    ): Result<Unit, PlayerAnimationError> {
        if (!server.isPrimaryThread) return offMain("applyEquipAnimation")
        // Animation-only per port KDoc; we don't touch inventory. The
        // online-player check still runs so an offline-target use case
        // surfaces UnknownPlayer rather than silently publishing a ghost
        // event for a player who can't see it.
        onlinePlayer(playerId) ?: return unknownPlayer(playerId)
        eventBus.publish(
            PlayerEquipped(playerId = playerId, slot = slot, item = item, atTick = tickClock()),
        )
        return Result.success(Unit)
    }

    override fun setSneaking(
        playerId: PlayerId,
        sneaking: Boolean,
    ): Result<Unit, PlayerAnimationError> {
        if (!server.isPrimaryThread) return offMain("setSneaking")
        val player = onlinePlayer(playerId) ?: return unknownPlayer(playerId)
        val previous = sneakStates[playerId]
        if (previous == sneaking) return Result.success(Unit)
        sneakStates[playerId] = sneaking
        player.isSneaking = sneaking
        eventBus.publish(
            PlayerSneakChanged(playerId = playerId, sneaking = sneaking, atTick = tickClock()),
        )
        return Result.success(Unit)
    }

    override fun setSleeping(
        playerId: PlayerId,
        world: WorldId,
        position: Vec3,
        sleeping: Boolean,
    ): Result<Unit, PlayerAnimationError> {
        if (!server.isPrimaryThread) return offMain("setSleeping")
        val player = onlinePlayer(playerId) ?: return unknownPlayer(playerId)
        val previous = sleepStates[playerId]
        if (previous == sleeping) return Result.success(Unit)
        if (sleeping) {
            val w = server.getWorld(world.value)
                ?: return Result.failure(PlayerAnimationError.Platform("world-not-loaded"))
            val loc = Location(w, position.x, position.y, position.z)
            // force=true bypasses Bukkit's "valid bed" guard so cinematic /
            // emote callers can lay players on arbitrary tiles.
            player.sleep(loc, /* force = */ true)
        } else {
            // setSpawnLocation=false: waking from a non-bed should not
            // hijack the player's vanilla spawn point.
            player.wakeup(/* setSpawnLocation = */ false)
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
        if (!server.isPrimaryThread) return offMain("setSitting")
        val player = onlinePlayer(playerId) ?: return unknownPlayer(playerId)
        val previous = sitStates[playerId] ?: SitTarget.None
        if (previous == target) return Result.success(Unit)

        // Clear any synthetic stand from a previous Position mount before
        // applying the new target (covers Position → None, Position →
        // Vehicle, and Position → other-Position).
        clearSyntheticStand(playerId)

        when (target) {
            is SitTarget.None -> {
                player.leaveVehicle()
                sitStates.remove(playerId)
            }
            is SitTarget.Vehicle -> {
                val vehicle = server.getEntity(target.entity.id)
                    ?: return Result.failure(PlayerAnimationError.Platform("vehicle-entity-not-found"))
                vehicle.addPassenger(player)
                sitStates[playerId] = target
            }
            is SitTarget.Position -> {
                val world = player.world
                val loc = Location(world, target.position.x, target.position.y, target.position.z)
                val stand = world.spawn(loc, ArmorStand::class.java).apply {
                    com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities.mark(this, "sit:${playerId.uuid}")
                    isInvisible = true
                    isMarker = true
                    isSmall = true
                    setGravity(false)
                }
                stand.addPassenger(player)
                sitStands[playerId] = stand.uniqueId
                sitStates[playerId] = target
            }
        }

        eventBus.publish(
            PlayerSitChanged(playerId = playerId, target = target, atTick = tickClock()),
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
        if (!server.isPrimaryThread) return offMain("playCustomAnimation")
        val player = onlinePlayer(playerId) ?: return unknownPlayer(playerId)
        if (animation.value == "hide-main-hand") return hideHand(playerId, player, durationTicks)
        if (animation.value == "swing-main-hand") {
            // One native swing: duration gates zero but does not retime the client's arm animation.
            return if (durationTicks == 0L) Result.success(Unit) else swingArm(playerId, PlayerHand.MainHand)
        }
        if (animation.value != "crouch") return Result.failure(PlayerAnimationError.UnknownCustomAnimation(animation))
        if (durationTicks == 0L) return Result.success(Unit)
        val clock = scheduler ?: return Result.failure(PlayerAnimationError.Platform("Animation scheduler unavailable"))
        val previous = poseOverrides[playerId]?.takeIf { it.player === player }
        val state = PoseOverride(player, previous?.previous ?: player.pose, previous?.fixed ?: player.hasFixedPose())
        poseOverrides[playerId] = state
        player.setPose(org.bukkit.entity.Pose.SNEAKING, true)
        clock.runLater(com.astarworks.astera.core.application.port.outbound.ticks(durationTicks)) {
            releasePose(playerId, state)
        }
        return Result.success(Unit)
    }

    override fun sneakState(playerId: PlayerId): Boolean? = sneakStates[playerId]

    override fun sitState(playerId: PlayerId): SitTarget = sitStates[playerId] ?: SitTarget.None

    // ------------------------------------------------------------------ helpers

    private fun onlinePlayer(playerId: PlayerId): Player? = server.getPlayer(playerId.uuid)

    private fun offMain(method: String): Result<Unit, PlayerAnimationError> {
        log.warn("PaperPlayerAnimationGateway.{} called off main-thread; returning Platform(off-main)", method)
        return Result.failure(PlayerAnimationError.Platform("off-main"))
    }

    private fun unknownPlayer(playerId: PlayerId): Result<Unit, PlayerAnimationError> =
        Result.failure(PlayerAnimationError.UnknownPlayer(playerId))

    /**
     * If a synthetic armor stand was spawned for this player, remove it
     * and clear the tracking entry. No-op when the player has none.
     */
    private fun clearSyntheticStand(playerId: PlayerId) {
        val standId = sitStands.remove(playerId) ?: return
        server.getEntity(standId)?.remove()
    }
}
