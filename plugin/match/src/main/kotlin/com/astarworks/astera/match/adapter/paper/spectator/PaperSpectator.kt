package com.astarworks.astera.match.adapter.paper.spectator

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.spectator.ISpectator
import com.astarworks.astera.core.application.port.outbound.spectator.SpectatorError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.GameMode
import org.bukkit.Server
import org.bukkit.entity.Player
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * Paper concrete of [ISpectator] (Wave 6c-§D, ADR-0160).
 *
 * Translates the vendor-neutral port operations into Bukkit
 * `GameMode.SPECTATOR` swaps + `Player.setSpectatorTarget(target)` calls.
 *
 * # Main-thread requirement
 *
 * Bukkit `Player.setGameMode` and `Player.setSpectatorTarget` must run on
 * the main thread (Folia regional contract; matches the constraint
 * documented on `PaperBlockStateGateway`, `PaperBarrierGateway`). The
 * adapter checks region ownership on every entrypoint:
 *  - On-main: applies the mutation synchronously and returns the result.
 *  - Off-main: returns `Result.failure(SpectatorError.Unsupported("off-main"))`.
 *    The contract is the same as the other Paper adapters — defensive
 *    re-dispatch is intentionally avoided so threading bugs surface in
 *    development. Callers that genuinely need off-main entry can wrap the
 *    call in `IScheduler.run(Duration.ZERO, ...)` themselves.
 *
 * # Idempotency
 *
 * [startSpectating] short-circuits when the viewer is already in
 * `GameMode.SPECTATOR`; [stopSpectating] short-circuits when the viewer is
 * already not spectating. [releaseCameraTarget] returns success when no
 * target is attached.
 *
 * # ADR-0149 §C invariant
 *
 * The adapter never reads or writes player HP, scoreboard, currency, or
 * any other Astera-owned state. The only side effect is the Bukkit gamemode
 * swap and the spectator-target attachment.
 */
public class PaperSpectator(
    private val server: Server,
    @Suppress("unused")
    private val scheduler: IScheduler,
) : ISpectator {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun startSpectating(viewer: PlayerId): Result<Unit, SpectatorError> {
        if (server.getPlayer(viewer.uuid)?.let { org.bukkit.Bukkit.isOwnedByCurrentRegion(it) } != true) return Result.failure(SpectatorError.Unsupported("off-main"))
        val player = resolvePlayer(viewer)
            ?: return Result.failure(SpectatorError.ViewerNotOnline(viewer))
        if (player.gameMode == GameMode.SPECTATOR) return Result.success(Unit)
        player.gameMode = GameMode.SPECTATOR
        if (log.isDebugEnabled) {
            log.debug("PaperSpectator.startSpectating: {} -> SPECTATOR", viewer)
        }
        return Result.success(Unit)
    }

    override fun stopSpectating(viewer: PlayerId): Result<Unit, SpectatorError> {
        if (server.getPlayer(viewer.uuid)?.let { org.bukkit.Bukkit.isOwnedByCurrentRegion(it) } != true) return Result.failure(SpectatorError.Unsupported("off-main"))
        val player = resolvePlayer(viewer)
            ?: return Result.failure(SpectatorError.ViewerNotOnline(viewer))
        if (player.gameMode != GameMode.SPECTATOR) return Result.success(Unit)
        // Vanilla drops the spectator-target on gamemode change; doing it
        // explicitly here keeps Paper and Fake observably equivalent.
        runCatching { player.spectatorTarget = null }
        player.gameMode = GameMode.SURVIVAL
        if (log.isDebugEnabled) {
            log.debug("PaperSpectator.stopSpectating: {} -> SURVIVAL", viewer)
        }
        return Result.success(Unit)
    }

    override fun setCameraTarget(
        viewer: PlayerId,
        target: PlayerId,
    ): Result<Unit, SpectatorError> {
        if (server.getPlayer(viewer.uuid)?.let { org.bukkit.Bukkit.isOwnedByCurrentRegion(it) } != true) return Result.failure(SpectatorError.Unsupported("off-main"))
        val viewerPlayer = resolvePlayer(viewer)
            ?: return Result.failure(SpectatorError.ViewerNotOnline(viewer))
        if (viewerPlayer.gameMode != GameMode.SPECTATOR) {
            return Result.failure(SpectatorError.Unsupported("viewer-not-spectating"))
        }
        val targetPlayer = resolvePlayer(target)
            ?: return Result.failure(SpectatorError.TargetNotOnline(target))
        if (targetPlayer.gameMode == GameMode.SPECTATOR) {
            return Result.failure(SpectatorError.TargetNotSpectatable(target, "target-is-spectator"))
        }
        if (targetPlayer.world.uid != viewerPlayer.world.uid) {
            return Result.failure(SpectatorError.TargetNotSpectatable(target, "different-world"))
        }
        viewerPlayer.spectatorTarget = targetPlayer
        if (log.isDebugEnabled) {
            log.debug("PaperSpectator.setCameraTarget: {} -> {}", viewer, target)
        }
        return Result.success(Unit)
    }

    override fun releaseCameraTarget(viewer: PlayerId): Result<Unit, SpectatorError> {
        if (server.getPlayer(viewer.uuid)?.let { org.bukkit.Bukkit.isOwnedByCurrentRegion(it) } != true) return Result.failure(SpectatorError.Unsupported("off-main"))
        val player = resolvePlayer(viewer)
            ?: return Result.failure(SpectatorError.ViewerNotOnline(viewer))
        // Bukkit accepts setSpectatorTarget(null) when not in SPECTATOR; treat
        // as a no-op rather than a failure so callers can issue release
        // unconditionally during cleanup paths.
        if (player.spectatorTarget == null) return Result.success(Unit)
        player.spectatorTarget = null
        if (log.isDebugEnabled) {
            log.debug("PaperSpectator.releaseCameraTarget: {}", viewer)
        }
        return Result.success(Unit)
    }

    override fun isSpectating(viewer: PlayerId): Boolean {
        val player = resolvePlayer(viewer) ?: return false
        return player.gameMode == GameMode.SPECTATOR
    }

    private fun resolvePlayer(viewer: PlayerId): Player? = server.getPlayer(viewer.uuid)

    public companion object {
        /** Exposed for KDoc references; the round-trip strategy is callers' choice. */
        public val OFF_MAIN_ROUNDTRIP_DELAY: Duration = Duration.ZERO
    }
}
