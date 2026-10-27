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
     * One RELATIVE camera kick for `camera_shake`: [dx] degrees of yaw (positive = turn right), [dy] degrees of pitch
     * (positive = look down), added to wherever the player's view currently is. The mechanic plans pulses whose kicks sum
     * to zero (`ShakeProfile`), so the host must apply each one as a delta, never as an absolute rotation.
     */
    public fun cameraKick(target: PlayerId, dx: Double, dy: Double)
}
