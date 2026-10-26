package com.astarworks.astera.core.application.eventbus.listeners.afk

import com.astarworks.astera.core.application.port.outbound.IModerationService
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerAfkKicked
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.moderation.ModerationAction
import com.astarworks.astera.core.domain.moderation.ModerationActionId
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * On [PlayerAfkKicked], record an audit-trail [ModerationAction.Kick]
 * with a stable system-issuer (Wave 5complete, ADR-0117 §Axis 3).
 *
 * # Why this matters
 *
 * Wave 5q's [com.astarworks.astera.core.application.service.afk.AfkSweepScheduler]
 * publishes [PlayerAfkKicked] when a player crosses the inactivity
 * threshold (default 2 min), but nothing subscribed in the production
 * wiring — the kick was a logical signal with no enforcement record.
 * This listener bridges to the moderation infrastructure so AFK kicks
 * appear in `IModerationService.history(player)` alongside admin-issued
 * kicks. The Paper enforcement (`Player.kick(...)`) lands in Wave 6b
 * once Wave 6 wires the `Ban` connection listener; until then this
 * listener writes the audit row and the player remains connected
 * (which matches the Wave 5h pattern — domain decision now, Paper
 * enforcement later).
 *
 * # Issuer
 *
 * Uses [SYSTEM_ISSUER_ID] — a fixed sentinel `PlayerId(UUID(0,0))`
 * shared with other system-initiated actions. Permission gating still
 * lives in [com.astarworks.astera.core.application.usecase.moderation.ApplyModerationActionUseCase]
 * — admins running `/astera mod history` see the system as the issuer
 * and can filter on it.
 *
 * **Bypass**: we call [IModerationService.apply] directly instead of
 * going through `ApplyModerationActionUseCase`, because the system
 * issuer has no permission entry and the use case's
 * `IPermissionService.check` would deny. AFK kicks are infrastructure,
 * not a moderator action — the permission gate doesn't apply.
 */
public class KickPlayerOnAfkKickedListener(
    private val moderationService: IModerationService,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerAfkKicked) {
        val action = ModerationAction.Kick(
            id = ModerationActionId.random(),
            playerId = event.playerId,
            issuedBy = SYSTEM_ISSUER_ID,
            reasonKey = REASON_KEY,
            issuedAt = Instant.now(clock),
        )
        com.astarworks.astera.core.application.port.outbound.IoScope.launch {
            when (val outcome = moderationService.apply(action)) {
                is Result.Success -> log.info(
                    "AFK auto-kick recorded for {} (idle {} s).",
                    event.playerId,
                    event.durationSec,
                )

                is Result.Failure -> log.warn(
                    "AFK auto-kick record failed for {}: {}",
                    event.playerId,
                    outcome.error,
                )
            }
        }
    }

    public companion object {
        /** Sentinel issuer id for system-initiated moderation actions. */
        public val SYSTEM_ISSUER_ID: PlayerId = PlayerId(UUID(0L, 0L))

        public val REASON_KEY: MessageKey = MessageKey("astera.mod.reason.afk-auto-kick")
    }
}
