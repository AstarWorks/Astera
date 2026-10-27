package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.damage.PerStackBonus
import com.astarworks.astera.core.domain.damage.StackHolder
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.skill.condition.Condition
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.BoolValue
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/** PM-W3: loop stride, status stacks, damage.per_stack, dash_strike and hit_stop through the real dispatcher. */
class ComboMechanicsScenarioTest {

    private val rupture = StatusEffectId("rupture")
    private val poise = StatusEffectId("poise")
    private val tremor = StatusEffectId("tremor")
    private val immunity = StatusEffectId("immunity")

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

    private fun stack(id: StatusEffectId, who: Targeter, n: Int) =
        Mechanic.ApplyStatus(who, id, IntValue.Literal(400), stackCount = IntValue.Literal(n))

    private fun ScenarioScope.stacks(entity: SimEntity, id: StatusEffectId): Int =
        sim.services.getOrThrow<IStatusEffectContainer>().stacks(entity.id, id)

    @Test
    fun `loop stride spreads iterations over time`() {
        scenario("combo-loop-stride") {
            val caster = player("caster", team = "red")
            run(
                caster, null,
                Mechanic.Loop(
                    targets = Targeter.Caster,
                    count = IntValue.Literal(3),
                    strideTicks = IntValue.Literal(5),
                    mechanics = listOf(stack(poise, Targeter.Caster, 1)),
                ),
            )
            assertThat(stacks(caster, poise)).isEqualTo(1)
            advance(5)
            assertThat(stacks(caster, poise)).isEqualTo(2)
            advance(5)
            assertThat(stacks(caster, poise)).isEqualTo(3)
        }
    }

    @Test
    fun `stack_count adds several stacks capped at max, remove_status consumes them`() {
        scenario("combo-stacks") {
            val caster = player("caster", team = "red")
            val target = player("target", at = Vec3(0.0, groundY, 6.0), team = "blue")

            run(caster, target, stack(rupture, Targeter.Target, 4))
            assertThat(stacks(target, rupture)).isEqualTo(4)
            run(caster, target, stack(rupture, Targeter.Target, 20))
            assertThat(stacks(target, rupture)).isEqualTo(10)

            run(caster, target, Mechanic.RemoveStatus(Targeter.Target, rupture, stacks = IntValue.Literal(3)))
            assertThat(stacks(target, rupture)).isEqualTo(7)
            run(caster, target, Mechanic.RemoveStatus(Targeter.Target, rupture))
            assertThat(stacks(target, rupture)).isZero()
        }
    }

    @Test
    fun `has_status min_stacks gates a mechanic`() {
        scenario("combo-min-stacks") {
            val caster = player("caster", team = "red")
            val target = player("target", at = Vec3(0.0, groundY, 6.0), team = "blue")
            val gated = Mechanic.IfThen(
                targets = Targeter.Caster,
                predicate = Condition.HasStatus(Targeter.Target, tremor, minStacks = 3),
                then_ = listOf(Mechanic.Ignite(Targeter.Target, IntValue.Literal(40))),
            )
            run(caster, target, stack(tremor, Targeter.Target, 2), gated)
            assertThat(target.fireTicks).isZero()
            run(caster, target, stack(tremor, Targeter.Target, 1), gated)
            assertThat(target.fireTicks).isEqualTo(40)
        }
    }

    @Test
    fun `per_stack adds damage per stack and consume spends them`() {
        scenario("combo-per-stack") {
            val caster = player("caster", team = "red")
            val plain = player("plain", at = Vec3(0.0, groundY, 6.0), team = "blue")
            val marked = player("marked", at = Vec3(3.0, groundY, 6.0), team = "blue")
            val hit = { consume: Boolean ->
                Mechanic.Damage(
                    Targeter.Target,
                    listOf(
                        DamageComponent(
                            NumericValue.Literal(2.0),
                            DamageAttribute.PHYSICAL,
                            perStack = PerStackBonus(rupture, amount = 1.0, on = StackHolder.TARGET, consume = consume),
                        ),
                    ),
                )
            }
            val plainBefore = hp(plain)
            val markedBefore = hp(marked)

            run(caster, marked, stack(rupture, Targeter.Target, 4))
            run(caster, plain, hit(false))
            run(caster, marked, hit(true))

            val plainLoss = plainBefore - hp(plain)
            val markedLoss = markedBefore - hp(marked)
            assertThat(plainLoss).isGreaterThan(0.0)
            assertThat(markedLoss).isGreaterThan(plainLoss)
            assertThat(stacks(marked, rupture)).isZero()
        }
    }

    @Test
    fun `dash_strike moves the caster, hits once, and drops immunity afterwards`() {
        scenario("combo-dash-strike") {
            val caster = player("caster", at = Vec3(0.0, groundY, 0.0), team = "red")
            caster.direction = Vec3(0.0, 0.0, 1.0)
            val target = player("target", at = Vec3(0.0, groundY, 3.0), team = "blue")
            val dash = Mechanic.DashStrike(
                distance = NumericValue.Literal(6.0),
                ticks = IntValue.Literal(3),
                radius = NumericValue.Literal(1.0),
                onHit = listOf(stack(tremor, Targeter.Target, 1)),
            )

            run(caster, null, dash)
            // First step is immediate and grants immunity for the dash.
            assertThat(caster.position.z).isCloseTo(2.0, Offset.offset(1e-6))
            assertThat(sim.services.getOrThrow<IStatusEffectContainer>().has(caster.id, immunity)).isTrue()
            advance(5)

            assertThat(caster.position.z).isCloseTo(6.0, Offset.offset(1e-6))
            assertThat(stacks(target, tremor)).isEqualTo(1)
            assertThat(sim.services.getOrThrow<IStatusEffectContainer>().has(caster.id, immunity)).isFalse()
        }
    }

    @Test
    fun `dash_strike with stop_before_target strikes at contact for any distance and a sideways-offset target`() {
        for ((distance, sideways) in listOf(3.5 to 0.0, 5.4 to 0.0, 7.0 to 0.0, 5.4 to 1.0)) {
            scenario("combo-dash-strike-contact-$distance-$sideways") {
                val caster = player("caster", at = Vec3(0.0, groundY, 0.0), team = "red")
                caster.direction = Vec3(0.0, 0.0, 1.0)
                val target = player("target", at = Vec3(sideways, groundY, distance), team = "blue")
                run(
                    caster, null,
                    Mechanic.DashStrike(
                        distance = NumericValue.Literal(9.0),
                        ticks = IntValue.Literal(3),
                        radius = NumericValue.Literal(0.8),
                        invulnerable = BoolValue.Literal(false),
                        stopBeforeTarget = 1.0,
                        onHit = listOf(stack(tremor, Targeter.Target, 1)),
                    ),
                )
                advance(5)

                assertThat(stacks(target, tremor)).`as`("hit at distance $distance, sideways $sideways").isEqualTo(1)
                // sharp stop: the caster ends one block short of the target, not past it
                assertThat(caster.position.z).isCloseTo(distance - 1.0, Offset.offset(1e-6))
            }
        }
    }

    @Test
    fun `dash_strike with stop_before_target runs its full distance when nothing is on the line`() {
        scenario("combo-dash-strike-contact-miss") {
            val caster = player("caster", at = Vec3(0.0, groundY, 0.0), team = "red")
            caster.direction = Vec3(0.0, 0.0, 1.0)
            val target = player("target", at = Vec3(3.0, groundY, 5.0), team = "blue")
            run(
                caster, null,
                Mechanic.DashStrike(
                    distance = NumericValue.Literal(9.0),
                    ticks = IntValue.Literal(3),
                    radius = NumericValue.Literal(0.8),
                    invulnerable = BoolValue.Literal(false),
                    stopBeforeTarget = 1.0,
                    onHit = listOf(stack(tremor, Targeter.Target, 1)),
                ),
            )
            advance(5)

            assertThat(stacks(target, tremor)).isZero()
            assertThat(caster.position.z).isCloseTo(9.0, Offset.offset(1e-6))
        }
    }

    @Test
    fun `dash_strike without hit_once keeps hitting while it overlaps`() {
        scenario("combo-dash-strike-multi") {
            val caster = player("caster", at = Vec3(0.0, groundY, 0.0), team = "red")
            caster.direction = Vec3(0.0, 0.0, 1.0)
            val target = player("target", at = Vec3(0.0, groundY, 3.0), team = "blue")
            run(
                caster, null,
                Mechanic.DashStrike(
                    distance = NumericValue.Literal(6.0),
                    ticks = IntValue.Literal(3),
                    radius = NumericValue.Literal(2.5),
                    hitOnce = BoolValue.Literal(false),
                    invulnerable = BoolValue.Literal(false),
                    onHit = listOf(stack(tremor, Targeter.Target, 1)),
                ),
            )
            advance(5)
            assertThat(stacks(target, tremor)).isGreaterThan(1)
        }
    }

    @Test
    fun `hit_stop holds targets and optionally the caster`() {
        scenario("combo-hit-stop") {
            val caster = player("caster", team = "red")
            val target = player("target", at = Vec3(0.0, groundY, 6.0), team = "blue")

            run(caster, target, Mechanic.HitStop(Targeter.Target, IntValue.Literal(3)))
            assertThat(target.frozenTicks).isEqualTo(3)
            assertThat(caster.frozenTicks).isZero()

            run(caster, target, Mechanic.HitStop(Targeter.Target, IntValue.Literal(4), includeCaster = BoolValue.Literal(true)))
            assertThat(caster.frozenTicks).isEqualTo(4)
        }
    }
}
