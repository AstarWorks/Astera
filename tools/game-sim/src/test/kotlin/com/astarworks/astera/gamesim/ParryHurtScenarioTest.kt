package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skill.SkillId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The shared `parry` part through the real damage pipeline (ApplyDamageUseCase's incoming interceptor -> running casts -> `hurt` transition with
 * `absorb: true`), on the real wiring and the shipped couture-rapier-def: a hit from an attacker inside the guard window is absorbed (no damage,
 * the riposte state runs and cuts the attacker 7 ticks later); an attacker behind the caster of a part with a limited arc is not parried.
 */
class ParryHurtScenarioTest {
    private fun hit(sim: GameSim, victim: SimEntity, attacker: SimEntity): Result<*, *> {
        val damage = checkNotNull(sim.combat.applyDamage)
        val profile = DamageProfile.single(10.0, DamageAttribute.PHYSICAL)
        return damage.execute(DamageAttempt(victim.id, profile, DamageSource.Skill(SkillId("husk-claw"), attacker.id)))
    }

    @Test
    fun `a hit inside the guard window of couture-rapier-def is absorbed and the riposte cuts the attacker`() {
        scenario("parry-hurt-front") {
            val caster = player("caster", team = "red")
            val husk = mob("husk", at = Vec3(0.0, groundY, 2.5), team = "blue")
            caster.holds("couture-rapier")
            caster.faces(husk)
            sim.setMaxHealth(husk, 500.0)
            sim.setMaxHealth(caster, 500.0)
            caster.cast("couture-rapier-def")
            advance(11)
            val casterHp = hp(caster)
            assertThat(sim.skillEngine.castLog).describedAs("cast log").contains("couture-rapier-def")
            assertThat(sim.skillEngine.problems).describedAs("problems before").isEmpty()
            val result = hit(sim, caster, husk)
            assertThat(result).describedAs("the parried hit is refused by the pipeline: $result").isInstanceOf(Result.Failure::class.java)
            assertThat(hp(caster)).describedAs("no damage on the parrying caster").isEqualTo(casterHp)
            advance(20)
            val cut = eventsOf<DamageDealt>().filter { it.target == husk.id }.map { it.resolved.finalAmount }
            assertThat(cut).describedAs("riposte_hit damages the attacker").contains(30.0)
            assertThat(sim.skillEngine.problems).isEmpty()
        }
    }

    @Test
    fun `a hit from behind a limited-arc guard is not parried`() {
        scenario("parry-hurt-behind") {
            val caster = player("caster", team = "red")
            val front = mob("front", at = Vec3(0.0, groundY, 2.5), team = "blue")
            val husk = mob("husk", at = Vec3(0.0, groundY, -2.5), team = "blue")
            caster.holds("bastion-greatsword")
            caster.faces(front)
            sim.setMaxHealth(caster, 500.0)
            caster.cast("bastion-greatsword-def")
            advance(6)
            assertThat(sim.skillEngine.castLog).contains("bastion-greatsword-def")
            val before = hp(caster)
            assertThat(hit(sim, caster, husk)).describedAs("arc 110: a hit from behind lands").isInstanceOf(Result.Success::class.java)
            assertThat(hp(caster)).isLessThan(before)
            // the same guard does catch the attacker in front of it
            val casterHp = hp(caster)
            assertThat(hit(sim, caster, front)).describedAs("a hit from the front is absorbed").isInstanceOf(Result.Failure::class.java)
            assertThat(hp(caster)).isEqualTo(casterHp)
            assertThat(sim.skillEngine.problems).isEmpty()
        }
    }
}
