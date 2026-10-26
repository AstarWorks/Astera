package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IDamageIndicatorGateway
import com.astarworks.astera.core.domain.hud.DamageIndicatorStyle
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.CopyOnWriteArrayList

/** Records every indicator (simulator / tests); draws nothing. */
public class InMemoryDamageIndicatorGateway : IDamageIndicatorGateway {

    public data class Shown(
        val viewer: PlayerId,
        val target: PlayerId,
        val amount: Int,
        val style: DamageIndicatorStyle,
    )

    private val log = CopyOnWriteArrayList<Shown>()

    override fun show(viewer: PlayerId, target: PlayerId, amount: Int, style: DamageIndicatorStyle) {
        log += Shown(viewer, target, amount, style)
    }

    public fun shown(): List<Shown> = log.toList()
}
