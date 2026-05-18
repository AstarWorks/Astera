package com.astarworks.astera.combat.testfixtures

import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId

/** Test fixture: records every impulse for assertion. */
public class FakeKnockbackGateway : IKnockbackGateway {
    public val applied: MutableList<Pair<PlayerId, Vec3>> = mutableListOf()

    override fun applyImpulse(target: PlayerId, impulse: Vec3) {
        applied += target to impulse
    }
}
