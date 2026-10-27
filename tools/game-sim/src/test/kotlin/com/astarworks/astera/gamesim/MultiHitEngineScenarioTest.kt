package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.skill.condition.Condition
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.targeter.DirectionRef
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.BoolValue
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Engine fixes for Limbus-style multi-hit skills: i-frame bypass, filter, radial knockback, live pose on delayed beats. */
class MultiHitEngineScenarioTest {

    private fun ScenarioScope.run(caster: SimEntity, target: SimEntity?, vararg mechanics: Mechanic) {
        val dispatcher = checkNotNull(sim.combat.mechanicDispatcher) { "dispatcher not wired" }
        dispatcher.dispatch(
            mechanics.toList(),
            MechanicContext(
                actorId = caster.id,
                targetId = target?.id,
                origin = caster.position,
                worldId = caster.world,
                pose = PlayerPose(caster.world, caster.position, caster.eyePosition, caster.direction),
            ),
        )
    }

    private fun hit(delay: Int, ignore: Boolean, targets: Targeter = Targeter.Target, snapshot: Boolean = false) = Mechanic.Damage(
        targets = targets,
        components = listOf(DamageComponent(NumericValue.Literal(2.0), DamageAttribute.PHYSICAL)),
        ignoreInvulnerability = BoolValue.Literal(ignore),
        delayTicks = IntValue.Literal(delay),
        snapshotPose = snapshot,
    )

    @Test
    fun `ignore_invulnerability lands every beat inside the i-frame window`() {
        scenario("multihit-iframes") {
            val caster = player("caster", team = "red")
            val target = player("target", at = Vec3(0.0, groundY, 4.0), team = "blue")
            val before = hp(target)

            run(caster, target, hit(0, true), hit(2, true), hit(4, true))
            advance(6)

            assertThat(before - hp(target)).isEqualTo(6.0)
        }
    }

    @Test
    fun `without the flag later beats are dropped by the i-frame window`() {
        scenario("multihit-iframes-default") {
            val caster = player("caster", team = "red")
            val target = player("target", at = Vec3(0.0, groundY, 4.0), team = "blue")
            val before = hp(target)

            run(caster, target, hit(0, false), hit(2, false), hit(4, false))
            advance(6)

            assertThat(before - hp(target)).isEqualTo(2.0)
        }
    }

    @Test
    fun `filter keeps only candidates that satisfy the condition`() {
        scenario("filter-has-status") {
            val caster = player("caster", team = "red")
            val marked = player("marked", at = Vec3(3.0, groundY, 0.0), team = "blue")
            val clean = player("clean", at = Vec3(-3.0, groundY, 0.0), team = "blue")
            val nearby = Targeter.NearbyEntities(Targeter.Caster, NumericValue.Literal(10.0))

            run(caster, marked, Mechanic.Freeze(Targeter.Target, IntValue.Literal(100)))
            run(
                caster, null,
                Mechanic.Ignite(
                    targets = Targeter.Filter(nearby, Condition.HasStatus(Targeter.Target, StatusEffectId("freeze"))),
                    ticks = IntValue.Literal(40),
                ),
            )

            assertThat(marked.fireTicks).isEqualTo(40)
            assertThat(clean.fireTicks).isZero()
        }
    }

    @Test
    fun `nearby target limit applies after excluding caster and zero selects nobody`() {
        scenario("nearby-target-limit") {
            val caster = player("caster", team = "red")
            val first = player("first", at = Vec3(2.0, groundY, 0.0), team = "blue")
            val second = player("second", at = Vec3(3.0, groundY, 0.0), team = "blue")
            val nearby = Targeter.NearbyEntities(
                Targeter.Caster, NumericValue.Literal(10.0), maxCount = IntValue.Literal(1), excludeCaster = true,
            )
            run(caster, null, Mechanic.Ignite(nearby, IntValue.Literal(40)))
            assertThat(caster.fireTicks).isZero()
            assertThat(listOf(first.fireTicks, second.fireTicks).count { it == 40 }).isEqualTo(1)
            run(caster, null, Mechanic.Ignite(nearby.copy(maxCount = IntValue.Literal(0)), IntValue.Literal(80)))
            assertThat(listOf(caster.fireTicks, first.fireTicks, second.fireTicks)).doesNotContain(80)
        }
    }

    @Test
    fun `limited cone consistently chooses the nearest candidate for a beat`() {
        scenario("cone-target-limit") {
            val caster = player("caster", team = "red")
            val far = mob("far", Vec3(0.0, groundY, 3.0), team = "blue")
            val near = mob("near", Vec3(0.0, groundY, 2.0), team = "blue")
            val cone = Targeter.Cone(
                Targeter.Caster, DirectionRef.FromCaster, NumericValue.Literal(75.0),
                NumericValue.Literal(4.0), maxCount = 1,
            )
            run(caster, null, hit(0, true, cone), Mechanic.Ignite(cone, IntValue.Literal(40)))
            assertThat(hp(near)).isEqualTo(com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP - 2.0)
            assertThat(near.fireTicks).isEqualTo(40)
            assertThat(hp(far)).isEqualTo(com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP)
            assertThat(far.fireTicks).isZero()
            run(caster, null, hit(0, true, cone.copy(maxCount = 0)))
            assertThat(hp(near)).isEqualTo(com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP - 2.0)
        }
    }

    @Test
    fun `approach dash stops before a close target without extending its empty range`() {
        for (targetZ in listOf(2.5, 8.0, -2.0)) {
            scenario("approach-dash-$targetZ") {
                val caster = player("caster", team = "red")
                mob("target", Vec3(0.0, groundY, targetZ), team = "blue")
                run(caster, null, Mechanic.DashStrike(
                    distance = NumericValue.Literal(5.0), ticks = IntValue.Literal(5),
                    invulnerable = BoolValue.Literal(false), stopBeforeTarget = 1.8,
                ))
                advance(10)
                assertThat(caster.position.z).isCloseTo(
                    if (targetZ == 2.5) 0.7 else 5.0, org.assertj.core.data.Offset.offset(0.0001),
                )
            }
        }
    }

    @Test
    fun `away_from_caster pushes each target radially and toward_caster pulls`() {
        scenario("radial-propel") {
            val caster = player("caster", team = "red")
            val east = player("east", at = Vec3(4.0, groundY, 0.0), team = "blue")
            val north = player("north", at = Vec3(0.0, groundY, 4.0), team = "blue")
            val all = Targeter.NearbyEntities(Targeter.Caster, NumericValue.Literal(10.0))

            run(caster, null, Mechanic.Propel(all, DirectionRef.AwayFromCaster(vertical = 0.5), NumericValue.Literal(1.0)))
            assertThat(east.velocity).isEqualTo(Vec3(1.0, 0.5, 0.0))
            assertThat(north.velocity).isEqualTo(Vec3(0.0, 0.5, 1.0))

            run(caster, null, Mechanic.SetVelocity(all, DirectionRef.TowardCaster(), NumericValue.Literal(2.0)))
            assertThat(east.velocity).isEqualTo(Vec3(-2.0, 0.0, 0.0))
            assertThat(north.velocity).isEqualTo(Vec3(0.0, 0.0, -2.0))
        }
    }

    @Test
    fun `delayed beat follows the caster's current look, snapshot_pose keeps the cast pose`() {
        for (snapshot in listOf(false, true)) {
            scenario("delayed-live-pose-$snapshot") {
                val caster = player("caster", team = "red", facing = Vec3(1.0, 0.0, 0.0))
                val target = player("target", at = Vec3(0.0, groundY, 4.0), team = "blue")
                val before = hp(target)
                val ray = Targeter.ForwardRay(Targeter.Caster, NumericValue.Literal(10.0))

                run(caster, null, hit(5, false, ray, snapshot))
                caster.lookAt(target.position + Vec3(0.0, 1.0, 0.0)) // turns toward the target during the delay
                advance(6)

                if (snapshot) assertThat(hp(target)).isEqualTo(before) else assertThat(hp(target)).isEqualTo(before - 2.0)
            }
        }
    }

    @Test
    fun `knockback and hit_stop in either order release the impulse after the hold`() {
        for (propelFirst in listOf(true, false)) {
            scenario("hitstop-order-$propelFirst") {
                val caster = player("caster", team = "red")
                val target = player("target", at = Vec3(0.0, groundY, 4.0), team = "blue")
                val propel = Mechanic.Propel(Targeter.Target, DirectionRef.Up, NumericValue.Literal(1.0))
                val stop = Mechanic.HitStop(Targeter.Target, IntValue.Literal(5))

                if (propelFirst) run(caster, target, propel, stop) else run(caster, target, stop, propel)
                assertThat(target.velocity).isEqualTo(Vec3.ZERO)

                advance(6)
                assertThat(target.velocity).isEqualTo(Vec3(0.0, 1.0, 0.0))
            }
        }
    }

    @Test
    fun `a delayed mechanic evaluates its condition when it fires`() {
        scenario("condition-at-fire-time") {
            val caster = player("caster", team = "red")
            val target = player("target", at = Vec3(0.0, groundY, 4.0), team = "blue")
            val burn = Mechanic.Ignite(
                targets = Targeter.Target,
                ticks = IntValue.Literal(40),
                delayTicks = IntValue.Literal(5),
                condition = Condition.HasStatus(Targeter.Target, StatusEffectId("freeze")),
            )

            run(caster, target, burn) // not frozen yet at cast time
            advance(2)
            run(caster, target, Mechanic.Freeze(Targeter.Target, IntValue.Literal(100)))
            advance(5)

            assertThat(target.fireTicks).isEqualTo(40)
        }
    }

    @Test
    fun `restore_resource refunds the caster's pool`() {
        scenario("restore-resource") {
            val caster = player("caster", team = "red")
            sim.services.get<com.astarworks.astera.core.application.port.outbound.IResourcePoolService>()!!
                .spendResource(caster.id, com.astarworks.astera.core.domain.resource.ResourceType.Energy, 10)
            val before = energy(caster)

            run(
                caster, null,
                Mechanic.RestoreResource(com.astarworks.astera.core.domain.resource.ResourceType.Energy, IntValue.Literal(4)),
            )

            assertThat(energy(caster)).isEqualTo(before + 4)
        }
    }
}
