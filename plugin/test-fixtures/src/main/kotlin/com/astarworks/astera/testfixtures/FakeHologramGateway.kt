package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.HologramError
import com.astarworks.astera.core.application.port.outbound.IHologramGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hologram.Hologram
import com.astarworks.astera.core.domain.hologram.HologramBrightness
import com.astarworks.astera.core.domain.hologram.HologramColor
import com.astarworks.astera.core.domain.hologram.HologramHandle
import com.astarworks.astera.core.domain.hologram.HologramInterpolation
import com.astarworks.astera.core.domain.hologram.HologramKind
import com.astarworks.astera.core.domain.hologram.HologramTransform
import com.astarworks.astera.core.domain.hologram.HologramVisibilityScope
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IHologramGateway] for use case tests (Wave 5h, ADR-0079 §1).
 *
 * Beyond storing live holograms in a [ConcurrentHashMap], every operation
 * is appended to [operations] as a [HologramOp] so tests can assert on the
 * full call sequence (not just the final state). Production
 * `InMemoryHologramGateway` drops the history list to avoid an unbounded
 * memory leak in long-running servers.
 *
 * Test cleanup: call [clear] in `@AfterEach` if reusing one instance across
 * tests — the gateway has no per-test lifecycle scope on its own.
 *
 * Backed by [ConcurrentHashMap] because Wave 6b's `PaperHologramGateway`
 * will receive cross-thread events (Bukkit packet listeners, Folia regions)
 * and we want the test double to share that mental model.
 */
public class FakeHologramGateway(
    /**
     * Returns the currently-online player set (Wave 5h-fix2 [viewers]
     * resolution for `Everyone` scope). Default = empty.
     */
    private val onlinePlayersProvider: () -> Set<PlayerId> = { emptySet() },
    /**
     * Returns the player members of `(matchId, teamId)` (Wave 5h-fix2
     * [viewers] resolution for `PerTeam` scope). Default = always empty.
     */
    private val teamMembersProvider: (MatchId, TeamId) -> Set<PlayerId> = { _, _ -> emptySet() },
) : IHologramGateway {

    /**
     * Operation history. Tests assert on this list to verify the use case
     * called the gateway with the right arguments in the right order.
     */
    public sealed interface HologramOp {
        public data class Spawned(val handle: HologramHandle, val hologram: Hologram) : HologramOp
        public data class Despawned(val handle: HologramHandle) : HologramOp
        public data class TransformUpdated(val handle: HologramHandle, val transform: HologramTransform) : HologramOp
        public data class KindUpdated(val handle: HologramHandle, val kind: HologramKind) : HologramOp
        public data class Teleported(val handle: HologramHandle, val newPosition: Vec3, val newWorld: WorldId?) : HologramOp
        public data class VisibilityUpdated(val handle: HologramHandle, val scope: HologramVisibilityScope) : HologramOp
        public data class BrightnessUpdated(val handle: HologramHandle, val brightness: HologramBrightness?) : HologramOp
        public data class InterpolationUpdated(val handle: HologramHandle, val interpolation: HologramInterpolation) : HologramOp
        public data class GlowColorUpdated(val handle: HologramHandle, val color: HologramColor?) : HologramOp
    }

    private val live: ConcurrentHashMap<HologramHandle, Hologram> = ConcurrentHashMap()
    private val ops: MutableList<HologramOp> = mutableListOf()

    override fun spawn(hologram: Hologram): Result<HologramHandle, HologramError> {
        val handle = HologramHandle(UUID.randomUUID().toString())
        live[handle] = hologram
        ops += HologramOp.Spawned(handle, hologram)
        return Result.success(handle)
    }

    override fun despawn(handle: HologramHandle): Result<Unit, HologramError> {
        // Per port KDoc: despawn is idempotent — unknown handle returns Success.
        live.remove(handle)
        ops += HologramOp.Despawned(handle)
        return Result.success(Unit)
    }

    override fun updateTransform(
        handle: HologramHandle,
        transform: HologramTransform,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(transform = transform)
        ops += HologramOp.TransformUpdated(handle, transform)
        return Result.success(Unit)
    }

    override fun updateKind(
        handle: HologramHandle,
        kind: HologramKind,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(kind = kind)
        ops += HologramOp.KindUpdated(handle, kind)
        return Result.success(Unit)
    }

    override fun teleport(
        handle: HologramHandle,
        newPosition: Vec3,
        newWorld: WorldId?,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(
            position = newPosition,
            worldId = newWorld ?: current.worldId,
        )
        ops += HologramOp.Teleported(handle, newPosition, newWorld)
        return Result.success(Unit)
    }

    override fun updateVisibility(
        handle: HologramHandle,
        scope: HologramVisibilityScope,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(visibilityScope = scope)
        ops += HologramOp.VisibilityUpdated(handle, scope)
        return Result.success(Unit)
    }

    override fun updateBrightness(
        handle: HologramHandle,
        brightness: HologramBrightness?,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(brightness = brightness)
        ops += HologramOp.BrightnessUpdated(handle, brightness)
        return Result.success(Unit)
    }

    override fun updateInterpolation(
        handle: HologramHandle,
        interpolation: HologramInterpolation,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(interpolation = interpolation)
        ops += HologramOp.InterpolationUpdated(handle, interpolation)
        return Result.success(Unit)
    }

    override fun updateGlowColor(
        handle: HologramHandle,
        color: HologramColor?,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(glowColor = color)
        ops += HologramOp.GlowColorUpdated(handle, color)
        return Result.success(Unit)
    }

    override fun viewers(handle: HologramHandle): Collection<PlayerId> {
        val hologram = live[handle] ?: return emptyList()
        return when (val scope = hologram.visibilityScope) {
            HologramVisibilityScope.Everyone -> onlinePlayersProvider()
            is HologramVisibilityScope.PerPlayer -> scope.playerIds
                .intersect(onlinePlayersProvider().ifEmpty { scope.playerIds })
                .toList()
            is HologramVisibilityScope.PerTeam -> teamMembersProvider(scope.matchId, scope.teamId)
                .toList()
        }
    }

    override fun all(): Collection<HologramHandle> = live.keys.toList()

    /** Test accessor: snapshot of the hologram currently associated with [handle], or `null` if despawned/unknown. */
    public fun current(handle: HologramHandle): Hologram? = live[handle]

    /** Test accessor: immutable snapshot of every operation invoked so far, in call order. */
    public fun operations(): List<HologramOp> = ops.toList()

    /** Test cleanup hook — drops live state and history. Useful in `@AfterEach`. */
    public fun clear() {
        live.clear()
        ops.clear()
    }
}
