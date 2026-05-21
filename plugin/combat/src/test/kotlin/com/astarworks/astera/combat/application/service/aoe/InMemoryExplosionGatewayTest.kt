package com.astarworks.astera.combat.application.service.aoe

import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.domain.aoe.BlockDamagePolicy
import com.astarworks.astera.combat.domain.aoe.ExplosionError
import com.astarworks.astera.combat.domain.aoe.ExplosionSpec
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.aoe.ExplosionTriggered
import com.astarworks.astera.combat.testfixtures.FakeKnockbackGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryExplosionGatewayTest {

    private val world = WorldId("test")
    private val center = Vec3(10.0, 64.0, 10.0)
    private val attacker = PlayerId(UUID.randomUUID())
    private val victimA = PlayerId(UUID.randomUUID())
    private val victimB = PlayerId(UUID.randomUUID())

    private fun spec(
        radius: Double = 5.0,
        damageBase: Double = 8.0,
        knockbackMultiplier: Double = 1.0,
        blockDamage: BlockDamagePolicy = BlockDamagePolicy.None,
    ): ExplosionSpec = ExplosionSpec(
        center = center,
        world = world,
        radius = radius,
        knockbackMultiplier = knockbackMultiplier,
        blockDamage = blockDamage,
        damageProfile = DamageProfile.single(damageBase, DamageAttribute.PHYSICAL),
        source = DamageSource.Explosion(center, radius),
    )

    private class StubNearbyQuery(private val players: List<PlayerId>) : INearbyTargetQuery {
        override fun findInRadius(center: Vec3, radius: Double, world: WorldId?): List<PlayerId> = players
    }

    @Test
    fun `trigger applies damage uniformly to each nearby player`() {
        val bus = FakeDomainEventBus()
        val knockback = FakeKnockbackGateway()
        val scheduler = FakeScheduler()
        val gateway = InMemoryExplosionGateway(
            nearbyQuery = StubNearbyQuery(listOf(victimA, victimB)),
            knockbackGateway = knockback,
            eventBus = bus,
            scheduler = scheduler,
        )

        val outcome = gateway.trigger(spec(damageBase = 7.5))
        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        val result = (outcome as Result.Success).value
        assertThat(result.affectedPlayers)
            .containsExactlyInAnyOrder(victimA to 7.5, victimB to 7.5)
        assertThat(result.affectedBlocks).isZero
    }

    @Test
    fun `trigger publishes ExplosionTriggered with current tick`() {
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler(startTick = 1234L)
        val gateway = InMemoryExplosionGateway(
            nearbyQuery = StubNearbyQuery(listOf(victimA)),
            knockbackGateway = FakeKnockbackGateway(),
            eventBus = bus,
            scheduler = scheduler,
        )

        gateway.trigger(spec()).also { assertThat(it.isSuccess).isTrue }

        val event = bus.published.single() as ExplosionTriggered
        assertThat(event.world).isEqualTo(world)
        assertThat(event.center).isEqualTo(center)
        assertThat(event.atTick).isEqualTo(1234L)
        assertThat(event.affectedPlayers).hasSize(1)
        assertThat(event.affectedBlocks).isZero
    }

    @Test
    fun `trigger applies knockback impulse when multiplier positive`() {
        val knockback = FakeKnockbackGateway()
        val gateway = InMemoryExplosionGateway(
            nearbyQuery = StubNearbyQuery(listOf(victimA, victimB)),
            knockbackGateway = knockback,
            eventBus = FakeDomainEventBus(),
            scheduler = FakeScheduler(),
        )

        gateway.trigger(spec(knockbackMultiplier = 2.0))
        assertThat(knockback.applied).hasSize(2)
        knockback.applied.forEach { (_, impulse) ->
            // Default radial-unit (placeholder) is +Y for the InMemory impl.
            assertThat(impulse).isEqualTo(Vec3(0.0, 2.0, 0.0))
        }
    }

    @Test
    fun `trigger skips knockback when multiplier is zero`() {
        val knockback = FakeKnockbackGateway()
        val gateway = InMemoryExplosionGateway(
            nearbyQuery = StubNearbyQuery(listOf(victimA)),
            knockbackGateway = knockback,
            eventBus = FakeDomainEventBus(),
            scheduler = FakeScheduler(),
        )

        gateway.trigger(spec(knockbackMultiplier = 0.0))
        assertThat(knockback.applied).isEmpty()
    }

    @Test
    fun `trigger rejects radius above the cap`() {
        val gateway = InMemoryExplosionGateway(
            nearbyQuery = StubNearbyQuery(emptyList()),
            knockbackGateway = FakeKnockbackGateway(),
            eventBus = FakeDomainEventBus(),
            scheduler = FakeScheduler(),
            radiusCap = 4.0,
        )
        val outcome = gateway.trigger(spec(radius = 5.0))
        val error = (outcome as Result.Failure).error
        assertThat(error).isInstanceOf(ExplosionError.RadiusExceedsCap::class.java)
        val cap = error as ExplosionError.RadiusExceedsCap
        assertThat(cap.requested).isEqualTo(5.0)
        assertThat(cap.cap).isEqualTo(4.0)
    }

    @Test
    fun `trigger custom damage applier records mitigated damage`() {
        val applier = ExplosionDamageApplier { _, base, _ -> base * 0.5 }
        val gateway = InMemoryExplosionGateway(
            nearbyQuery = StubNearbyQuery(listOf(victimA)),
            knockbackGateway = FakeKnockbackGateway(),
            eventBus = FakeDomainEventBus(),
            scheduler = FakeScheduler(),
            damageApplier = applier,
        )

        val result = (gateway.trigger(spec(damageBase = 10.0)) as Result.Success).value
        assertThat(result.affectedPlayers.single()).isEqualTo(victimA to 5.0)
    }

    @Test
    fun `spec rejects negative radius`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            ExplosionSpec(
                center = center,
                world = world,
                radius = -0.01,
                blockDamage = BlockDamagePolicy.None,
                damageProfile = DamageProfile.single(1.0, DamageAttribute.PHYSICAL),
                source = DamageSource.Environment.Generic,
            )
        }
    }

    @Test
    fun `BlockDamagePolicy Astera rejects ratio out of range`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            BlockDamagePolicy.Astera(damageRatio = 1.5)
        }
    }

    @Suppress("unused")
    private val attackerSilencer = attacker // kept for parity / future tests
}
