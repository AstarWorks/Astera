package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
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
 */
public class NoOpKnockbackGateway : IKnockbackGateway {

    override fun applyImpulse(target: PlayerId, impulse: Vec3) {
        if (log.isDebugEnabled) {
            log.debug("NoOpKnockbackGateway: target={} impulse={}", target, impulse)
        }
    }

    public companion object {
        private val log = LoggerFactory.getLogger(NoOpKnockbackGateway::class.java)
    }
}
