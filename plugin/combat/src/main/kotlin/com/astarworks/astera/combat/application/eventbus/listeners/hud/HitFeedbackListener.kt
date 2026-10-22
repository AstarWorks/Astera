package com.astarworks.astera.combat.application.eventbus.listeners.hud

import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IDamageIndicatorGateway
import com.astarworks.astera.core.application.port.outbound.effect.IPlayerSoundGateway
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.hud.DamageIndicatorStyle
import kotlin.math.ceil

/**
 * Hit confirmation for the attacker (ADR-0206): a hit marker sound and a floating
 * damage number over the victim that only the attacker sees; a different sound when
 * the hit killed.
 *
 * Only player-on-player hits count: environmental and status-effect damage has no
 * attacker, and hitting yourself (explosion knock) gives no feedback.
 */
public class HitFeedbackListener(
    private val indicators: IDamageIndicatorGateway,
    private val sounds: IPlayerSoundGateway,
) {

    public fun onDamage(event: DamageDealt) {
        val attacker = event.attacker ?: return
        if (attacker == event.target) return
        val resolved = event.resolved
        val style = when {
            resolved.wasBlocked || resolved.finalAmount <= 0.0 -> DamageIndicatorStyle.BLOCKED
            resolved.wasCritical -> DamageIndicatorStyle.CRITICAL
            else -> DamageIndicatorStyle.NORMAL
        }
        val amount = if (style == DamageIndicatorStyle.BLOCKED) 0 else ceil(resolved.finalAmount).toInt()
        indicators.show(attacker, event.target, amount, style)
        sounds.playForViewer(
            attacker,
            when (style) {
                DamageIndicatorStyle.CRITICAL -> CRIT_SOUND
                DamageIndicatorStyle.BLOCKED -> BLOCKED_SOUND
                DamageIndicatorStyle.NORMAL -> HIT_SOUND
            },
        )
    }

    public fun onKilled(event: PlayerKilled) {
        val killer = event.killer ?: return
        if (killer == event.victim) return
        sounds.playForViewer(killer, KILL_SOUND)
    }

    public companion object {
        public val HIT_SOUND: SoundSpec = SoundSpec(key = "minecraft:entity.arrow.hit_player", volume = 0.4, pitch = 1.3)
        public val CRIT_SOUND: SoundSpec = SoundSpec(key = "minecraft:entity.player.attack.crit", volume = 0.6, pitch = 1.3)
        public val BLOCKED_SOUND: SoundSpec = SoundSpec(key = "minecraft:item.shield.block", volume = 0.4, pitch = 1.5)
        public val KILL_SOUND: SoundSpec = SoundSpec(key = "minecraft:entity.experience_orb.pickup", volume = 0.8, pitch = 0.7)
    }
}
