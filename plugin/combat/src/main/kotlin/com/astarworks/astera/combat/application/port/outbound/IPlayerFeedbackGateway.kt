package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Host-neutral player-facing text output for the messaging mechanics
 * (WP4: ActionBar, Title). Text is already rendered for the player's locale
 * (see `IMessageRenderer.renderText`). Mobs and offline players are ignored.
 */
public interface IPlayerFeedbackGateway {

    public fun sendActionBar(target: PlayerId, rendered: String)

    public fun sendTitle(
        target: PlayerId,
        title: String,
        subtitle: String?,
        fadeInTicks: Int,
        stayTicks: Int,
        fadeOutTicks: Int,
    )

    /**
     * One camera jolt for `CameraShake` (WP5); [intensity] in 0..1. The
     * mechanic repeats it every couple of ticks for the shake's duration, so
     * the host only nudges the view once per call.
     */
    public fun cameraKick(target: PlayerId, intensity: Double)
}
