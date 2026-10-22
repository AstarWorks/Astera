package com.astarworks.astera.match.application.service.death

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.spectator.ISpectator
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/** Why [SpectatorTargetCycler] could not attach the camera. */
public sealed class CycleError {
    public data object NotInMatch : CycleError()
    public data object NoTargets : CycleError()
}

/**
 * Left / right click camera cycling for spectators (dead players in their respawn
 * countdown and eliminated teams).
 *
 * Candidates are the viewer's living teammates; when none are left (an eliminated
 * team) every other living player of the match. "Living" = not spectating, not
 * eliminated ([isOut]). Candidates are ordered by player id so the cycle is stable.
 */
public class SpectatorTargetCycler(
    private val matches: IMatchRepository,
    private val spectator: ISpectator,
    private val hud: IHudGateway? = null,
    private val players: IPlayerGateway? = null,
    private val isOut: (Match, PlayerId) -> Boolean = { _, _ -> false },
) {
    private val current: MutableMap<PlayerId, PlayerId> = ConcurrentHashMap()

    /** Attach to the first candidate (used right after a team is eliminated). */
    public fun focusFirst(viewer: PlayerId): Result<PlayerId, CycleError> = cycle(viewer, forward = true)

    /** Move the camera to the next ([forward]) or previous candidate. */
    public fun cycle(viewer: PlayerId, forward: Boolean): Result<PlayerId, CycleError> {
        val match = matches.findMatchOf(viewer) ?: return Result.failure(CycleError.NotInMatch)
        val candidates = candidates(match, viewer)
        if (candidates.isEmpty()) return Result.failure(CycleError.NoTargets)

        val start = current[viewer]?.let { candidates.indexOf(it) } ?: -1
        val n = candidates.size
        for (step in 1..n) {
            val index = when {
                start >= 0 -> Math.floorMod(start + if (forward) step else -step, n)
                forward -> step - 1
                else -> n - step
            }
            val target = candidates[index]
            if (spectator.setCameraTarget(viewer, target) is Result.Success) {
                current[viewer] = target
                announce(viewer, target)
                return Result.success(target)
            }
        }
        return Result.failure(CycleError.NoTargets)
    }

    /** Forget [viewer]'s target (respawned / match ended). */
    public fun clear(viewer: PlayerId) {
        current.remove(viewer)
    }

    /** Living players [viewer] can currently watch, in cycle order. */
    public fun candidates(match: Match, viewer: PlayerId): List<PlayerId> {
        fun alive(id: PlayerId) = id != viewer && !isOut(match, id) && !spectator.isSpectating(id)
        val mates = match.teams.firstOrNull { viewer in it.members }?.members.orEmpty().filter(::alive)
        val pool = mates.ifEmpty { match.teams.flatMap { it.members }.filter(::alive) }
        return pool.sortedBy { it.uuid.toString() }
    }

    private fun announce(viewer: PlayerId, target: PlayerId) {
        val gateway = hud ?: return
        val name = players?.nameOf(target) ?: target.uuid.toString().take(8)
        gateway.showFor(
            viewer,
            HudLayout.ActionBar(
                component = HudComponent.Text(SPECTATING_KEY, HudColor.YELLOW, mapOf("player" to name)),
                ttlTicks = 40,
                priority = 2, // same as the respawn countdown so the countdown's next tick replaces it
            ),
        )
    }

    private companion object {
        val SPECTATING_KEY = MessageKey("astera.spectate.target")
    }
}
