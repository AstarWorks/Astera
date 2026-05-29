package com.astarworks.astera.core.application.eventbus.listeners.afk

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerAfkKicked
import com.astarworks.astera.core.domain.event.PlayerAfkWarned
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * Push an ActionBar warning on [PlayerAfkWarned] and clear it once the
 * player either recovers or is finally kicked (Wave I 5match-wireup-fix,
 * ADR-0126 §D).
 *
 * # Why this listener exists
 *
 * Wave 5q's [com.astarworks.astera.core.application.service.afk.AfkSweepScheduler]
 * publishes [PlayerAfkWarned] when a player crosses the warning
 * threshold (default 30 s of inactivity, 60 s before the auto-kick at
 * the 90 s mark). Pre-Wave-I that signal was record-only —
 * [KickPlayerOnAfkKickedListener] handled the eventual moderation row
 * but the player got no chance to dismiss the warning, because nothing
 * surfaced it before the kick fired. Private-beta playtesters who left
 * the keyboard for ~70 s came back to a kick message with no warning;
 * this listener closes the feedback loop.
 *
 * # Render
 *
 * One ActionBar push per [PlayerAfkWarned]. The
 * `astera.match.afk.warning` i18n key resolves to a short MiniMessage
 * string ("You will be kicked in {seconds}s for AFK"); the
 * `secondsRemaining` field on the event is not currently woven into the
 * MessageKey because [HudComponent.Text] is i18n-key-only — the message
 * catalog inlines the canonical "~60 s" warning text. Wave 6b's
 * `HudComponent.TextWithPlaceholders` (deferred) will thread the value
 * through.
 *
 * # Recovery / kick cleanup
 *
 * - Recovery → no event (AfkSweepScheduler intentionally doesn't
 *   publish Active transitions). We can't clear the ActionBar
 *   reactively without a "recovered" event. ActionBar slots fade
 *   naturally (`ttlTicks`); the next sweep that finds Active simply
 *   stops re-publishing Warning, so no further pushes happen and the
 *   fade finishes the job. Acceptable for Phase 1.
 * - [PlayerAfkKicked] → explicit [IHudGateway.clearFor] on
 *   [HudRegion.ACTION_BAR] so the warning doesn't linger between the
 *   moderation audit row and the eventual Wave 6b Paper kick.
 *
 * # Failure mode
 *
 * Per-player HUD push failures log at DEBUG and continue — typically
 * means the player just quit, which is exactly the state we're
 * detecting anyway.
 */
public class AfkWarningHudListener(
    private val hudGateway: IHudGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun onWarned(event: PlayerAfkWarned) {
        val layout = HudLayout.ActionBar(
            component = HudComponent.Text(messageKey = KEY_WARNING),
            ttlTicks = ACTIONBAR_TTL,
        )
        when (val outcome = hudGateway.showFor(event.playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug(
                "AfkWarningHud push failed for {} (secondsRemaining={}): {}",
                event.playerId, event.secondsRemaining, outcome.error,
            )
        }
    }

    public fun onKicked(event: PlayerAfkKicked) {
        when (val outcome = hudGateway.clearFor(event.playerId, HudRegion.ACTION_BAR)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug(
                "AfkWarningHud clear failed for {} (durationSec={}): {}",
                event.playerId, event.durationSec, outcome.error,
            )
        }
    }

    public companion object {
        /**
         * Re-show every ~2 s so the message stays visible until either the
         * sweeper stops emitting (player recovered) or the kick fires.
         */
        public const val ACTIONBAR_TTL: Int = 40

        public val KEY_WARNING: MessageKey = MessageKey("astera.match.afk.warning")
    }
}
