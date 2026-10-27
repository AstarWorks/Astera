package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.domain.damage.KnockbackPush
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * No-op binding of [IKnockbackGateway] for environments without a Paper
 * runtime (unit tests, balance simulations, the default DI graph before
 * Wave 6 wires `PaperKnockbackGateway`).
 *
 * Logs at DEBUG so unit-test runs stay quiet by default but the impulse
 * stream remains inspectable when needed.
 *
 * ADR-0136: exempt from the knockback-resistance contract documented on
 * [IKnockbackGateway.applyImpulse]. The contract applies only to
 * implementations that perform a physical write (e.g. `Player.setVelocity`
 * in `PaperKnockbackGateway`). NoOp logs the raw pre-resistance impulse so
 * unit tests can assert against the domain pipeline output directly
 * without simulating Bukkit's `Attribute.KNOCKBACK_RESISTANCE` aggregation.
 */
public class NoOpKnockbackGateway : IKnockbackGateway {

    override fun applyImpulse(target: PlayerId, impulse: Vec3) {
        if (log.isDebugEnabled) {
            log.debug("NoOpKnockbackGateway: target={} impulse={}", target, impulse)
        }
    }

    override fun applyPush(target: PlayerId, push: KnockbackPush) {
        if (log.isDebugEnabled) {
            log.debug("NoOpKnockbackGateway: target={} push={}", target, push)
        }
    }

    public companion object {
        private val log = LoggerFactory.getLogger(NoOpKnockbackGateway::class.java)
    }
}
