package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.DamageApplicationError
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryDamageGatewayTest {

    private val gateway = InMemoryDamageGateway()
    private val target = PlayerId(UUID.randomUUID())
    private val attacker = PlayerId(UUID.randomUUID())

    @Test
    fun `apply returns TargetOffline for every call (Phase 1 PoC stub)`() {
        val resolved = ResolvedDamage(
            finalAmount = 10.0,
            attribute = DamageAttribute.PHYSICAL,
            wasBlocked = false,
            wasCritical = false,
        )
        val source = DamageSource.Item(
            itemId = ItemId("test-sword"),
            attackerId = attacker,
        )

        val result = gateway.apply(target, resolved, source)

        assertThat(result.isFailure).isTrue()
        val error = (result as com.astarworks.astera.core.domain.Result.Failure).error
        assertThat(error).isInstanceOf(DamageApplicationError.TargetOffline::class.java)
        assertThat((error as DamageApplicationError.TargetOffline).targetId).isEqualTo(target)
    }
}
