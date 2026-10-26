package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.HudError
import com.astarworks.astera.core.application.port.outbound.IActionBarChannel
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * [IActionBarChannel] over the HUD gateway (ADR-0279): a post is an `ActionBar` layout in the
 * gateway's action-bar region, which the gateway composes with the HP status line.
 * [hud] is resolved per call so the channel can be built before the gateway is registered.
 */
public class HudActionBarChannel(private val hud: () -> IHudGateway?) : IActionBarChannel {

    override fun post(
        player: PlayerId,
        message: HudComponent,
        priority: Int,
        ttlTicks: Int,
    ): Result<Unit, HudError> {
        val gateway = hud() ?: return Result.Failure(HudError.RenderFailed("HUD gateway not available"))
        val layout = HudLayout.ActionBar(message, ttlTicks = ttlTicks.coerceIn(1, 1200), priority = priority.coerceAtLeast(0))
        return when (val shown = gateway.showFor(player, layout)) {
            is Result.Success -> Result.Success(Unit)
            is Result.Failure -> Result.Failure(shown.error)
        }
    }
}
