package com.astarworks.astera.match.application.eventbus.listeners.hud

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Respawn countdown HUD readout (Wave 5match-hud, ADR-0118 §7).
 *
 * Subscribes to [PlayerKilled] (Wave 5a, `:plugin:combat`) and pushes a
 * one-per-second action-bar countdown for the next [countdownSeconds]
 * seconds. Cancels the schedule early on [PlayerRespawned] in case the
 * player respawns via `/kill` or a different code path (admin revive).
 *
 * # Why not driven by RespawnUseCase?
 *
 * `RespawnUseCase.respawn(spec)` fires when the respawn *executes*, not
 * when it is *scheduled*. The "Respawning in 5..4..3.." HUD is
 * post-death pre-respawn — driven by death, terminated by either the
 * timer running out or the player respawning early. Subscribing to
 * `PlayerKilled` here matches the lifecycle the player experiences.
 *
 * # Per-player handle bookkeeping
 *
 * A second death before the first respawn (rare — protection prevents
 * it in PvP, but PvE / void death can hit it) cancels the prior
 * countdown to avoid overlapping schedules emitting decremented numbers
 * for the wrong death.
 */
public class HudUpdateOnRespawnScheduledListener(
    private val hudGateway: IHudGateway,
    private val scheduler: IScheduler,
    public val countdownSeconds: Int = DEFAULT_COUNTDOWN_SECONDS,
) {

    init {
        require(countdownSeconds in 1..30) {
            "countdownSeconds must be 1..30, was $countdownSeconds"
        }
    }

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * One entry per active countdown — the value is the list of pending
     * [IScheduler.TaskHandle]s so we can cancel them on early respawn /
     * second death.
     */
    private val active: MutableMap<PlayerId, MutableList<IScheduler.TaskHandle>> =
        ConcurrentHashMap()

    public fun onKilled(event: PlayerKilled) {
        startCountdown(event.victim)
    }

    public fun onRespawned(event: PlayerRespawned) {
        cancelCountdown(event.playerId)
    }

    /** Cancel every pending countdown. Idempotent. */
    public fun stop() {
        active.keys.toList().forEach { cancelCountdown(it) }
    }

    private fun startCountdown(playerId: PlayerId) {
        cancelCountdown(playerId)
        val tasks = mutableListOf<IScheduler.TaskHandle>()
        for (secondsLeft in countdownSeconds downTo 1) {
            val delayTicks = ((countdownSeconds - secondsLeft) * 20L)
            val handle = scheduler.runLaterTicks(delayTicks) {
                pushTick(playerId, secondsLeft)
            }
            tasks += handle
        }
        active[playerId] = tasks
    }

    private fun cancelCountdown(playerId: PlayerId) {
        active.remove(playerId)?.forEach { it.cancel() }
    }

    private fun pushTick(playerId: PlayerId, secondsLeft: Int) {
        val layout = HudLayout.ActionBar(
            component = HudComponent.Text(
                messageKey = MessageKey("astera.hud.respawn.countdown"),
                color = if (secondsLeft <= 1) HudColor.RED else HudColor.YELLOW,
            ),
            ttlTicks = TICK_TTL,
            priority = 2,
        )
        when (val outcome = hudGateway.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug(
                "Respawn-countdown HUD show failed for {} (secondsLeft={}): {}",
                playerId,
                secondsLeft,
                outcome.error,
            )
        }
    }

    public companion object {
        /** Default matches [com.astarworks.astera.match.application.usecase.respawn.RespawnSpec.protectionSeconds] default (5 s). */
        public const val DEFAULT_COUNTDOWN_SECONDS: Int = 5

        /** 25 ticks ≈ 1.25 s — covers the next-tick edge without flicker. */
        public const val TICK_TTL: Int = 25
    }
}
