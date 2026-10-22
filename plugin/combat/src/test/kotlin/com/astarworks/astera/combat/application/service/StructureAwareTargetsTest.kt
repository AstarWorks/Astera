package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.DamageApplicationError
import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.port.outbound.IEntityDamageGateway
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ProjectileOrigin
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.combat.domain.projectile.ProjectileId
import com.astarworks.astera.combat.testfixtures.FakeNearbyTargetQuery
import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP10: structures (pillars) join the target query and the entity-damage path. */
class StructureAwareTargetsTest {

    private val world = WorldId("world")
    private val pillar = PlayerId(UUID.randomUUID())
    private val mob = PlayerId(UUID.randomUUID())
    private val attacker = PlayerId(UUID.randomUUID())
    private val centre = Vec3(10.5, -59.5, 0.5)

    private class FakeStructures(val pillar: PlayerId, val centre: Vec3, val world: WorldId) : IStructureTargets {
        var hp = 50.0
        var rejection: IStructureTargets.Rejection? = null
        var lastAttacker: PlayerId? = null

        override fun findInRadius(center: Vec3, radius: Double, world: WorldId?) =
            if (center.distance(centre) <= radius) {
                listOf(IStructureTargets.StructureTarget(pillar, centre, this.world))
            } else {
                emptyList()
            }

        override fun isStructure(id: PlayerId) = id == pillar
        override fun targetAtBlock(world: WorldId?, blockPosition: Vec3) = null
        override fun damage(target: PlayerId, attacker: PlayerId?, amount: Double): Result<Double, IStructureTargets.Rejection> {
            lastAttacker = attacker
            rejection?.let { return Result.failure(it) }
            hp = (hp - amount).coerceAtLeast(0.0)
            return Result.success(hp)
        }

        override fun hitPoints(id: PlayerId): Double? = hp.takeIf { id == pillar }
        override fun maxHitPoints(id: PlayerId): Double? = 50.0.takeIf { id == pillar }
    }

    private val structures = FakeStructures(pillar, centre, world)

    private val source = DamageSource.Projectile(ProjectileId.random(), attacker, ProjectileOrigin.FromSkill(SkillId("s")))

    private fun resolved(amount: Double): ResolvedDamage = ResolvedDamage(
        finalAmount = amount,
        attribute = com.astarworks.astera.core.domain.damage.DamageAttribute.FIRE,
        wasBlocked = false,
        wasCritical = false,
    )

    @Test
    fun `nearby query adds standing structures to the host's targets`() {
        val base = FakeNearbyTargetQuery(listOf(mob))
        val query = StructureAwareNearbyTargetQuery(base) { structures }

        assertThat(query.findInRadius(centre, 3.0, world)).containsExactlyInAnyOrder(mob, pillar)
        assertThat(query.findInRadius(Vec3(100.0, 0.0, 0.0), 3.0, world)).containsExactly(mob)
        val nearby = query.findNearby(centre, 3.0, world).first { it.id == pillar }
        // feet position is half a block below the centre; one block tall
        assertThat(nearby.position).isEqualTo(Vec3(10.5, -60.0, 0.5))
        assertThat(nearby.height).isEqualTo(1.0)
    }

    @Test
    fun `nearby query without a structure registry is the host query`() {
        val base = FakeNearbyTargetQuery(listOf(mob))
        val query = StructureAwareNearbyTargetQuery(base) { null }
        assertThat(query.findInRadius(centre, 3.0, world)).containsExactly(mob)
    }

    @Test
    fun `entity gateway routes structure ids to the registry and everything else to the host`() {
        val host = object : IEntityDamageGateway {
            override fun isEntityTarget(target: PlayerId) = target == mob
            override fun apply(target: PlayerId, resolved: ResolvedDamage, source: DamageSource) =
                Result.success(DamageOutcome.HpReduced(7.0))
            override fun hitPoints(target: PlayerId): Double? = 7.0
        }
        val gateway = StructureAwareEntityDamageGateway(host) { structures }

        assertThat(gateway.isEntityTarget(pillar)).isTrue()
        assertThat(gateway.isEntityTarget(mob)).isTrue()
        assertThat(gateway.isEntityTarget(attacker)).isFalse()

        val hit = gateway.apply(pillar, resolved(20.0), source)
        assertThat((hit as Result.Success).value).isEqualTo(DamageOutcome.HpReduced(30.0))
        assertThat(structures.lastAttacker).isEqualTo(attacker)
        assertThat((gateway.apply(pillar, resolved(100.0), source) as Result.Success).value).isEqualTo(DamageOutcome.Killed)
        assertThat((gateway.apply(mob, resolved(1.0), source) as Result.Success).value).isEqualTo(DamageOutcome.HpReduced(7.0))
        assertThat(gateway.hitPoints(pillar)).isEqualTo(0.0)
        assertThat(gateway.hitPoints(mob)).isEqualTo(7.0)
        assertThat(gateway.maxHitPoints(pillar)).isEqualTo(50.0)
    }

    @Test
    fun `a rejected structure hit is a failure that changes nothing`() {
        structures.rejection = IStructureTargets.Rejection.FRIENDLY
        val gateway = StructureAwareEntityDamageGateway(null) { structures }

        val r = gateway.apply(pillar, resolved(20.0), source)

        assertThat((r as Result.Failure).error).isInstanceOf(DamageApplicationError.Immune::class.java)
        assertThat(structures.hp).isEqualTo(50.0)
    }

    @Test
    fun `without a host mob gateway only structures are entity targets`() {
        val gateway = StructureAwareEntityDamageGateway(null) { structures }
        assertThat(gateway.isEntityTarget(mob)).isFalse()
        assertThat((gateway.apply(mob, resolved(1.0), source) as Result.Failure).error)
            .isInstanceOf(DamageApplicationError.TargetOffline::class.java)
    }
}
