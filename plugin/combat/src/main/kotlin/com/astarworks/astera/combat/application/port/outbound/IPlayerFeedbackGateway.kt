package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.mechanic.ScreenOverlay

/**
 * Host-neutral player-facing text output for the messaging mechanics
 * (WP4: ActionBar, Title). Text is already rendered for the player's locale
 * (see `IMessageRenderer.renderText`). Mobs and offline players are ignored.
 */
public interface IPlayerFeedbackGateway {

    public fun postActionBar(target: PlayerId, rendered: String)

    public fun sendTitle(
        target: PlayerId,
        title: String,
        subtitle: String?,
        fadeInTicks: Int,
        stayTicks: Int,
        fadeOutTicks: Int,
    )

    /**
     * Full-screen cut-in (`screen_flash`): [overlay] glyph tinted [colorRgb] (0xRRGGBB), shown as a
     * title, so it replaces any title the player currently sees.
     */
    public fun sendScreenFlash(
        target: PlayerId,
        overlay: ScreenOverlay,
        colorRgb: Int,
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

    /** One directional camera kick: [dx] degrees of yaw, [dy] degrees of pitch (relative). Default: no-op. */
    public fun cameraKick(target: PlayerId, dx: Double, dy: Double) {}
}
