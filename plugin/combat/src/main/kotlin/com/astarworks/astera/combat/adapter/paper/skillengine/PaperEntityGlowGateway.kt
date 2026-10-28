package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.core.application.skillengine.runtime.ops.IEntityGlowGateway
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import java.util.UUID

/** `glow` op: the vanilla outline through the combat entity-effects gateway (v1 `IEntityEffectsGateway.setGlowing`). Only online, living players glow. */
public class PaperEntityGlowGateway(
    private val server: Server,
    private val effects: IEntityEffectsGateway,
) : IEntityGlowGateway {
    override fun isLivingPlayer(entity: UUID): Boolean = server.getPlayer(entity)?.let { it.isOnline && !it.isDead } ?: false

    override fun setGlowing(entity: UUID, glowing: Boolean) = effects.setGlowing(PlayerId(entity), glowing)
}
