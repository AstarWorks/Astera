package com.astarworks.astera.combat.application.eventbus.listeners.hud

import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IDamageIndicatorGateway
import com.astarworks.astera.core.application.port.outbound.effect.ISoundCuePlayer
import com.astarworks.astera.core.domain.effect.SoundCues
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
    private val sounds: ISoundCuePlayer,
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
        sounds.play(
            attacker,
            when (style) {
                DamageIndicatorStyle.CRITICAL -> SoundCues.COMBAT_CRIT
                DamageIndicatorStyle.BLOCKED -> SoundCues.COMBAT_BLOCKED
                DamageIndicatorStyle.NORMAL -> SoundCues.COMBAT_HIT
            },
        )
    }

    public fun onKilled(event: PlayerKilled) {
        val killer = event.killer ?: return
        if (killer == event.victim) return
        sounds.play(killer, SoundCues.COMBAT_KILL)
    }

}
