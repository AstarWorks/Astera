package com.astarworks.astera.combat.application.eventbus.listeners.ego

import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IEgoGauge

/**
 * Feeds the E.G.O gauge from combat (ADR-0231): the attacker gains
 * `gain_on_dealt` per landed hit, the victim gains `gain_on_taken` per hit taken,
 * and a death empties the victim's gauge.
 *
 * Blocked / zero hits and self-damage (explosion knock) give nothing, so a
 * shield wall or a self-hit loop cannot farm the ultimate.
 */
public class EgoGaugeCombatListener(
    private val gauge: IEgoGauge,
) {

    public fun onDamage(event: DamageDealt) {
        val resolved = event.resolved
        if (resolved.wasBlocked || resolved.finalAmount <= 0.0) return
        val attacker = event.attacker
        if (attacker == event.target) return
        if (attacker != null) gauge.gain(attacker, gauge.config.gainOnDealt)
        gauge.gain(event.target, gauge.config.gainOnTaken)
    }

    public fun onKilled(event: PlayerKilled) {
        gauge.reset(event.victim)
    }
}
