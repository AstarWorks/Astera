package com.astarworks.astera.combat.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * Per-tick cooldown HUD readout (Wave 5match-hud, ADR-0118 §1).
 *
 * # Why a polling scheduler instead of an event
 *
 * [ICooldownTracker] (Wave _ops, ADR-0082) is intentionally event-less —
 * `start` / `reset` / `tick` are imperative calls without a corresponding
 * `CooldownStarted` / `CooldownTick` event. The cooldown ticker would have
 * to emit on every tick for every (player, key) pair which is wasteful;
 * instead the tracker exposes `activeKeysFor(playerId)` and `remainingTicks`
 * so the HUD layer can pull the current state on its own cadence.
 *
 * Round 10 audit found no `CooldownStarted` event in the codebase and
 * Wave 5g posture explicitly forbids adding new event types when a pull
 * model works (cooldown ticks are 100% derivable from the tracker state).
 *
 * # Cadence
 *
 * Polls every 5 ticks (250 ms). At Phase 1 player counts (≤32) and typical
 * cooldown counts (≤4 active per player) this is ≤ 128 lookups per second
 * — negligible.
 *
 * # Lifecycle
 *
 * Constructor starts a [IScheduler.TaskHandle]; [stop] cancels it. The
 * plugin platform calls [stop] from `onDisable`.
 *
 * # Phase 1 stub
 *
 * Like the other Wave 5*-hud listeners, the Phase 1 `InMemoryHudGateway`
 * records calls + publishes `HudShown`; the Paper renderer materialises
 * the action bar in Wave 6b.
 */
public class HudUpdateOnCooldownTickListener(
    private val cooldownTracker: ICooldownTracker,
    private val sessions: IPlayerSessionRepository,
    private val hudGateway: IHudGateway,
    scheduler: IScheduler,
    pollPeriodTicks: Long = DEFAULT_POLL_PERIOD_TICKS,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val taskHandle: IScheduler.TaskHandle = scheduler.runRepeatingTicks(
        initialDelayTicks = pollPeriodTicks,
        periodTicks = pollPeriodTicks,
    ) {
        pushForAll()
    }

    /** Cancel the polling task. Idempotent: a cancelled handle is a no-op. */
    public fun stop() {
        taskHandle.cancel()
    }

    private fun pushForAll() {
        for (session in sessions.all()) {
            val activeKeys = cooldownTracker.activeKeysFor(session.playerId)
            if (activeKeys.isEmpty()) continue
            pushFor(session.playerId)
        }
    }

    private fun pushFor(playerId: PlayerId) {
        val layout = HudLayout.ActionBar(
            component = HudComponent.Text(
                messageKey = MessageKey("astera.hud.cooldown.summary"),
                color = HudColor.AQUA,
            ),
            ttlTicks = ACTIONBAR_TTL_TICKS,
        )
        when (val outcome = hudGateway.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug(
                "Cooldown HUD show failed for {}: {}",
                playerId,
                outcome.error,
            )
        }
    }

    public companion object {
        /**
         * 5 ticks (250 ms) is fast enough to look continuous for skill
         * cooldowns in the 1-10 second range without flooding the gateway.
         */
        public const val DEFAULT_POLL_PERIOD_TICKS: Long = 5L

        /**
         * 30 ticks (1.5 s) — ttl slightly longer than the poll period so a
         * single skipped pulse does not blank the action bar mid-cooldown.
         */
        public const val ACTIONBAR_TTL_TICKS: Int = 30
    }
}
