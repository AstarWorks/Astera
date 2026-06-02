package com.astarworks.astera.testfixtures.spectator

import com.astarworks.astera.core.application.port.outbound.spectator.ISpectator
import com.astarworks.astera.core.application.port.outbound.spectator.SpectatorError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory recording [ISpectator] for tests (Wave 6c-§D, ADR-0160).
 *
 * Tracks a set of players currently in spectator mode plus the optional
 * camera target attachment per viewer. Behaves observably like the Paper
 * adapter without touching Bukkit, so use-case tests that depend on
 * [ISpectator] run in the no-Paper harness.
 *
 * Online-status modelling: tests register an "online" set via
 * [setOnline] / [setOffline]. Any operation against an unregistered player
 * surfaces [SpectatorError.ViewerNotOnline] / [SpectatorError.TargetNotOnline]
 * — matching the Paper adapter's behaviour when `server.getPlayer(uuid)`
 * returns null.
 */
public class FakeSpectator : ISpectator {

    private val online: MutableSet<PlayerId> = LinkedHashSet()
    private val spectating: MutableSet<PlayerId> = LinkedHashSet()
    private val cameraTargets: MutableMap<PlayerId, PlayerId> = LinkedHashMap()

    /** Mark [playerId] as connected. Test setup helper. */
    public fun setOnline(playerId: PlayerId) {
        online.add(playerId)
    }

    /** Mark [playerId] as disconnected. Test teardown helper. */
    public fun setOffline(playerId: PlayerId) {
        online.remove(playerId)
        spectating.remove(playerId)
        cameraTargets.remove(playerId)
    }

    override fun startSpectating(viewer: PlayerId): Result<Unit, SpectatorError> {
        if (viewer !in online) return Result.failure(SpectatorError.ViewerNotOnline(viewer))
        spectating.add(viewer)
        return Result.success(Unit)
    }

    override fun stopSpectating(viewer: PlayerId): Result<Unit, SpectatorError> {
        if (viewer !in online) return Result.failure(SpectatorError.ViewerNotOnline(viewer))
        spectating.remove(viewer)
        cameraTargets.remove(viewer)
        return Result.success(Unit)
    }

    override fun setCameraTarget(
        viewer: PlayerId,
        target: PlayerId,
    ): Result<Unit, SpectatorError> {
        if (viewer !in online) return Result.failure(SpectatorError.ViewerNotOnline(viewer))
        if (viewer !in spectating) {
            return Result.failure(SpectatorError.Unsupported("viewer-not-spectating"))
        }
        if (target !in online) return Result.failure(SpectatorError.TargetNotOnline(target))
        if (target in spectating) {
            return Result.failure(SpectatorError.TargetNotSpectatable(target, "target-is-spectator"))
        }
        cameraTargets[viewer] = target
        return Result.success(Unit)
    }

    override fun releaseCameraTarget(viewer: PlayerId): Result<Unit, SpectatorError> {
        if (viewer !in online) return Result.failure(SpectatorError.ViewerNotOnline(viewer))
        cameraTargets.remove(viewer)
        return Result.success(Unit)
    }

    override fun isSpectating(viewer: PlayerId): Boolean = viewer in spectating

    /** Test inspector — returns the current camera target for [viewer], or null. */
    public fun cameraTargetOf(viewer: PlayerId): PlayerId? = cameraTargets[viewer]
}
