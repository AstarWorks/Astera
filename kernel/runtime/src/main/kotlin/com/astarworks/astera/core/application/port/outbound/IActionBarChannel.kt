package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * The single entry point for every transient action-bar message (ADR-0279).
 *
 * The action bar is one client surface shared by the always-on HP status line (the persistent
 * base layer) and every toast (star pickup, respawn line, refusals, hints). Whoever sends to it
 * directly erases the HP line until the next refresh, so nothing outside the compositor
 * (`PaperHudGateway`) may call the platform action-bar send; Konsist enforces that. Callers post
 * here instead. The compositor merges the message with the status line in one component, so the
 * HP block never leaves the screen, and re-sends it at the HUD cadence.
 *
 * Arbitration: a live message of higher [priority] is not overwritten by a lower one
 * (see `HudChannelPolicy`); equal or higher replaces. Priority 0 is reserved for persistent
 * readouts (cooldowns); toasts use >= 1.
 */
public interface IActionBarChannel {

    /** Post [message] to [player] for [ttlTicks]. A failure leaves the HUD as it was. */
    public fun post(
        player: PlayerId,
        message: HudComponent,
        priority: Int = DEFAULT_PRIORITY,
        ttlTicks: Int = DEFAULT_TTL_TICKS,
    ): Result<Unit, HudError>

    public companion object {
        /** Toast priority: above the cooldown readout (0). */
        public const val DEFAULT_PRIORITY: Int = 1

        /** Matches the previous per-caller default (2 s). */
        public const val DEFAULT_TTL_TICKS: Int = 40
    }
}

/** Post an i18n [key] (rendered per viewer by the compositor). */
public fun IActionBarChannel.post(
    player: PlayerId,
    key: MessageKey,
    placeholders: Map<String, String> = emptyMap(),
    priority: Int = IActionBarChannel.DEFAULT_PRIORITY,
    ttlTicks: Int = IActionBarChannel.DEFAULT_TTL_TICKS,
): Result<Unit, HudError> =
    post(player, HudComponent.Text(key, placeholders = placeholders), priority, ttlTicks)

/** Post an already rendered MiniMessage string. */
public fun IActionBarChannel.postRendered(
    player: PlayerId,
    rendered: String,
    priority: Int = IActionBarChannel.DEFAULT_PRIORITY,
    ttlTicks: Int = IActionBarChannel.DEFAULT_TTL_TICKS,
): Result<Unit, HudError> =
    post(player, HudComponent.Rendered(rendered), priority, ttlTicks)
