package com.astarworks.astera.core.application.service

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
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory [IHologramGateway] (Wave 5h, ADR-0079 §1).
 *
 * Wired by `AsteraCorePlugin` at priority 0 — Wave 6b's
 * `PaperHologramGateway` will register at higher priority and shadow this
 * impl on real servers, but Phase 1 PoC tooling (tutorial scaffolding,
 * spectator overlays) gets a working gateway from day one.
 *
 * **Behaviour vs `FakeHologramGateway`:** identical contract, no operation
 * history. The fake keeps a `MutableList<HologramOp>` for test assertions;
 * the production in-memory impl drops it to avoid an unbounded memory
 * leak in long-running servers — a long-lived match might call
 * `updateTransform` thousands of times per minute on a single hologram and
 * the history would grow without bound. If you need observability in
 * production, layer a decorator gateway over this one rather than baking it
 * in here.
 *
 * **Not thread-safe** beyond what [ConcurrentHashMap] provides. Astera's
 * scheduler contract means hologram mutation runs on the server main
 * thread; the concurrent map is precautionary for cross-thread
 * Wave 6b packet listeners.
 */
public class InMemoryHologramGateway : IHologramGateway {

    private val live: ConcurrentHashMap<HologramHandle, Hologram> = ConcurrentHashMap()

    override fun spawn(hologram: Hologram): Result<HologramHandle, HologramError> {
        val handle = HologramHandle(UUID.randomUUID().toString())
        live[handle] = hologram
        return Result.success(handle)
    }

    override fun despawn(handle: HologramHandle): Result<Unit, HologramError> {
        // Per port KDoc: despawn is idempotent — unknown handle returns Success.
        live.remove(handle)
        return Result.success(Unit)
    }

    override fun updateTransform(
        handle: HologramHandle,
        transform: HologramTransform,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(transform = transform)
        return Result.success(Unit)
    }

    override fun updateKind(
        handle: HologramHandle,
        kind: HologramKind,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(kind = kind)
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
        return Result.success(Unit)
    }

    override fun updateVisibility(
        handle: HologramHandle,
        scope: HologramVisibilityScope,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(visibilityScope = scope)
        return Result.success(Unit)
    }

    override fun updateBrightness(
        handle: HologramHandle,
        brightness: HologramBrightness?,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(brightness = brightness)
        return Result.success(Unit)
    }

    override fun updateInterpolation(
        handle: HologramHandle,
        interpolation: HologramInterpolation,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(interpolation = interpolation)
        return Result.success(Unit)
    }

    override fun updateGlowColor(
        handle: HologramHandle,
        color: HologramColor?,
    ): Result<Unit, HologramError> {
        val current = live[handle]
            ?: return Result.failure(HologramError.UnknownHandle(handle))
        live[handle] = current.copy(glowColor = color)
        return Result.success(Unit)
    }

    /**
     * In-memory impl returns an empty viewer set. The real population happens
     * in Wave 6b `PaperHologramGateway` which has access to `Server.onlinePlayers`
     * and per-match team rosters. Production in-process callers should treat
     * empty as "no information" rather than "no one viewing".
     */
    override fun viewers(handle: HologramHandle): Collection<PlayerId> = emptyList()

    override fun all(): Collection<HologramHandle> = live.keys.toList()
}
