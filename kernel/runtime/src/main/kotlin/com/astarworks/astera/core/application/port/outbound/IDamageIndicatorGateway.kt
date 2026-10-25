package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.hud.DamageIndicatorStyle
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Floating damage number over a hit player, seen **only by the attacker**
 * (ADR-0206): confirmation that a hit landed and how hard, without cluttering
 * anyone else's screen. The number rises and fades on its own after about a
 * second; callers fire and forget.
 */
public interface IDamageIndicatorGateway {

    /** Shows [amount] (whole HP, already rounded) over [target] to [viewer]. */
    public fun show(viewer: PlayerId, target: PlayerId, amount: Int, style: DamageIndicatorStyle)
}
