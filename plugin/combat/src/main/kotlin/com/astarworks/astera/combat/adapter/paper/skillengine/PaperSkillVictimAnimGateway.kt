package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.domain.damage.KnockbackPush
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVictimAnimGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import org.bukkit.entity.LivingEntity
import java.util.UUID
import kotlin.math.atan2

/**
 * [ISkillVictimAnimGateway]: vanilla hurt animation facing the attacker plus, for `stagger` / `stagger_back`, a
 * short push away through [IKnockbackGateway] (resistance honoured). Main thread only.
 */
public class PaperSkillVictimAnimGateway(
    private val server: Server,
    private val knockback: IKnockbackGateway,
) : ISkillVictimAnimGateway {

    override fun play(victim: UUID, anim: String, strength: Double, awayFrom: Vec3) {
        val e = server.getEntity(victim) as? LivingEntity ?: return
        val loc = e.location
        val dx = awayFrom.x - loc.x
        val dz = awayFrom.z - loc.z
        // Hurt tilt is relative to the attacker's direction.
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        when (anim) {
            "hurt" -> e.playHurtAnimation(yaw)
            "stagger", "stagger_back" -> {
                e.playHurtAnimation(yaw)
                if (strength > 0.0) knockback.applyPush(PlayerId(victim), KnockbackPush(awayFrom = awayFrom, strength = strength))
            }
            else -> error("victim_anim '$anim' passed the loader but the gateway has no reaction for it")
        }
    }
}
