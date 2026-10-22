package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.combat.domain.event.StatusApplied
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.skill.condition.Condition
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.targeter.DirectionRef
import com.astarworks.astera.core.domain.skill.targeter.LocationRef
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** WP4 (mechanics batch A) end to end through the real dispatcher, wiring and sim ports. */
class MechanicsBatchAScenarioTest {

    private fun ScenarioScope.run(caster: SimEntity, target: SimEntity?, vararg mechanics: Mechanic) {
        val dispatcher = checkNotNull(sim.combat.mechanicDispatcher) { "dispatcher not wired" }
        dispatcher.dispatch(
            mechanics.toList(),
            MechanicContext(
                actorId = caster.id,
                targetId = target?.id,
                origin = caster.position,
                worldId = caster.world,
                pose = com.astarworks.astera.core.domain.player.PlayerPose(
                    world = caster.world,
                    position = caster.position,
                    eyePosition = caster.eyePosition,
                    direction = caster.direction,
                ),
            ),
        )
    }

    @Test
    fun `freeze and silence stick until cleanse removes them`() {
        scenario("mechanics-freeze-then-cleanse") {
            val caster = player("caster", team = "red")
            val target = player("target", at = Vec3(0.0, groundY, 6.0), team = "blue")
            val container = sim.services.getOrThrow<IStatusEffectContainer>()
            val freeze = StatusEffectId("freeze")
            val silence = StatusEffectId("silence")

            run(
                caster, target,
                Mechanic.Freeze(Targeter.Target, IntValue.Literal(100)),
                Mechanic.Silence(Targeter.Target, IntValue.Literal(100)),
                Mechanic.Ignite(Targeter.Target, IntValue.Literal(60)),
            )
            assertThat(container.has(target.id, freeze)).isTrue()
            assertThat(container.has(target.id, silence)).isTrue()
            assertThat(target.frozenTicks).isEqualTo(100)
            assertThat(target.fireTicks).isEqualTo(60)
            // One application = one StatusApplied per status (it used to be two).
            assertThat(eventsOf<StatusApplied>().filter { it.target == target.id && it.effect.spec.id == freeze }).hasSize(1)

            advance(20)
            run(caster, target, Mechanic.Cleanse(Targeter.Target))

            assertThat(container.has(target.id, freeze)).isFalse()
            assertThat(container.has(target.id, silence)).isFalse()
            assertThat(target.frozenTicks).isZero()
            assertThat(target.fireTicks).isZero()
        }
    }

    @Test
    fun `leap launches the caster toward the destination along an arc`() {
        scenario("mechanics-leap") {
            val caster = player("caster", team = "red")
            val target = player("target", at = Vec3(8.0, groundY, 6.0), team = "blue")

            run(caster, target, Mechanic.Leap(Targeter.Caster, LocationRef.Target, arcHeight = NumericValue.Literal(2.0)))

            val v = caster.velocity
            assertThat(v.y).isGreaterThan(0.0)
            assertThat(v.x).isGreaterThan(0.0)
            assertThat(v.z).isGreaterThan(0.0)
            // The horizontal velocity keeps the heading to the destination (8, 6).
            assertThat(v.x / v.z).isCloseTo(8.0 / 6.0, org.assertj.core.data.Offset.offset(1e-9))

            run(caster, null, Mechanic.SetVelocity(Targeter.Caster, DirectionRef.Up, NumericValue.Literal(0.5)))
            assertThat(caster.velocity).isEqualTo(Vec3(0.0, 0.5, 0.0))
            run(caster, null, Mechanic.Propel(Targeter.Caster, DirectionRef.Up, NumericValue.Literal(0.25)))
            assertThat(caster.velocity).isEqualTo(Vec3(0.0, 0.75, 0.0))
        }
    }

    @Test
    fun `IsEnemy condition filters allies out of an ignite`() {
        scenario("mechanics-is-enemy-filter") {
            val caster = player("caster", team = "red")
            val ally = player("ally", at = Vec3(2.0, groundY, 0.0), team = "red")
            val enemy = player("enemy", at = Vec3(-2.0, groundY, 0.0), team = "blue")
            val burn = Mechanic.Ignite(
                targets = Targeter.Target,
                ticks = IntValue.Literal(40),
                condition = Condition.IsEnemy(Targeter.Caster, Targeter.Target),
            )

            run(caster, ally, burn)
            run(caster, enemy, burn)

            assertThat(ally.fireTicks).isZero()
            assertThat(enemy.fireTicks).isEqualTo(40)
        }
    }

    @Test
    fun `health below condition, title and action bar reach the player`() {
        scenario("mechanics-health-and-feedback") {
            val caster = player("caster", team = "red")
            val target = player("target", at = Vec3(0.0, groundY, 4.0), team = "blue")
            val hi = LocalizedText(mapOf(LocaleCode.EN to "Hello"))
            val finisher = Mechanic.IfThen(
                targets = Targeter.Caster,
                predicate = Condition.HealthBelow(Targeter.Target, NumericValue.Literal(0.5)),
                then_ = listOf(Mechanic.SendTitle(targets = Targeter.Caster, title = hi)),
            )

            run(caster, target, finisher)
            assertThat(sim.feedback.titles).isEmpty()

            sim.setMaxHealth(target, 20.0)
            sim.services.getOrThrow<com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway>()
                .setHealth(
                    target.id, 5.0,
                    com.astarworks.astera.core.domain.player.physiology.HealthChangeSource.Other("sim-setup"),
                )
            run(caster, target, finisher, Mechanic.SendActionBar(Targeter.Caster, hi))

            assertThat(sim.feedback.titles.map { it.title }).containsExactly("Hello")
            assertThat(sim.feedback.actionBars.map { it.second }).containsExactly("Hello")
        }
    }
}
