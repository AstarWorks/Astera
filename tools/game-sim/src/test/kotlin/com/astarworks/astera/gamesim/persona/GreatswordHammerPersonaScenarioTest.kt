package com.astarworks.astera.gamesim.persona

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.gamesim.ScenarioScope
import com.astarworks.astera.gamesim.SimEntity
import com.astarworks.astera.gamesim.scenario
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0230 persona weapons #5 `bastion-greatsword` (charge, banked by guarding) and #6
 * `verdict-hammer` (rupture "stakes" driven into the target). Every skill is cast through the
 * item binding (or `cast` for sneak + left click, which the simulator has no input for), must
 * damage a dummy where it is offensive, must spawn displays, apply / spend its stack as
 * designed, stay under 60 live displays, and leave no display behind.
 *
 * Damage assertions are lower bounds: a 10 percent critical hit can only raise a hit, and
 * a player's 20-tick invulnerability frames are why every follow-up hit sits 21+ ticks after the
 * previous one. The dummy gets 500 HP so a stacked finisher cannot kill it mid-test.
 */
class GreatswordHammerPersonaScenarioTest {

    private val charge = StatusEffectId("charge")
    private val rupture = StatusEffectId("rupture")

    private fun ScenarioScope.stacks(entity: SimEntity, id: StatusEffectId): Int =
        sim.services.getOrThrow<IStatusEffectContainer>().stacks(entity.id, id)

    private fun ScenarioScope.resist(entity: SimEntity, key: AttributeKey): Double =
        sim.services.getOrThrow<IPlayerAttributeContainerStore>().containerFor(entity.id).effectiveNumber(key)

    private fun ScenarioScope.fatten(entity: SimEntity, hp: Double = 500.0) {
        val container = sim.services.getOrThrow<IPlayerAttributeContainerStore>().containerFor(entity.id)
        container.set(Attributes.MAX_HP, AttributeValue.NumberValue(hp))
        container.set(Attributes.CURRENT_HP, AttributeValue.NumberValue(hp))
    }

    /** Pushes [count] stacks of [id] onto [who] through the real dispatcher (what a landed hit would do). */
    private fun ScenarioScope.addStacks(caster: SimEntity, who: SimEntity, id: StatusEffectId, count: Int) {
        val dispatcher = checkNotNull(sim.combat.mechanicDispatcher) { "dispatcher not wired" }
        dispatcher.dispatch(
            listOf(Mechanic.ApplyStatus(Targeter.Target, id, IntValue.Literal(400), stackCount = IntValue.Literal(count))),
            MechanicContext(
                actorId = caster.id,
                targetId = who.id,
                origin = caster.position,
                worldId = caster.world,
                pose = PlayerPose(caster.world, caster.position, caster.eyePosition, caster.direction),
            ),
        )
    }

    private class Duel(val caster: SimEntity, val target: SimEntity)

    /** [item] in the caster's hand, a fat dummy of the other team 3 blocks dead ahead. */
    private fun ScenarioScope.duel(item: String, targetZ: Double = 3.0): Duel {
        val caster = player("caster", team = "red")
        val target = player("target", at = Vec3(0.0, groundY, targetZ), team = "blue")
        caster.holds(item)
        caster.faces(target)
        fatten(target)
        return Duel(caster, target)
    }

    /** Steps [ticks] one at a time and returns the peak number of live displays. */
    private fun ScenarioScope.stepPeak(ticks: Int): Int {
        var peak = sim.displays.active.size
        repeat(ticks) {
            advance(1)
            peak = maxOf(peak, sim.displays.active.size)
        }
        return peak
    }

    /** Distinct damage events that landed on [who] (each beat / coin is its own event). */
    private fun ScenarioScope.hitsOn(who: SimEntity): Int = eventsOf<DamageDealt>().count { it.target == who.id }

    private fun ScenarioScope.assertDisplaysSpawnedAndGone() {
        assertThat(sim.displays.spawned).isNotEmpty
        assertThat(sim.displays.active).isEmpty()
        assertThat(sim.recorder.effects("display_remove")).hasSize(sim.recorder.effects("display_spawn").size)
    }

    // ===================================================================================
    // Bastion Greatsword
    // ===================================================================================

    @Test
    fun `bastion S1 lands two beats, banks one charge and leaves no display behind`() {
        scenario("persona-bastion-s1") {
            val d = duel("bastion-greatsword")
            val before = hp(d.target)

            d.caster.leftClick()
            advance(1)
            val first = before - hp(d.target)
            assertThat(first).isGreaterThanOrEqualTo(7.0)
            assertThat(stacks(d.caster, charge)).isEqualTo(1)
            assertThat(sim.displays.spawned).isNotEmpty

            val peak = stepPeak(25)
            val total = before - hp(d.target)
            assertThat(total).isGreaterThanOrEqualTo(16.0)
            assertThat(stacks(d.caster, charge)).isEqualTo(1)
            assertThat(hitsOn(d.target)).isEqualTo(2)
            assertThat(peak).isLessThanOrEqualTo(60)

            stepPeak(40)
            assertDisplaysSpawnedAndGone()
        }
    }

    @Test
    fun `bastion S1 second beat whiffs when the target backs off`() {
        scenario("persona-bastion-s1-whiff") {
            val d = duel("bastion-greatsword")
            val before = hp(d.target)

            d.caster.leftClick()
            advance(1)
            val first = before - hp(d.target)
            d.target.position = Vec3(0.0, groundY, 9.0)
            advance(40)

            assertThat(before - hp(d.target)).isEqualTo(first)
            assertThat(stacks(d.caster, charge)).isEqualTo(1)
        }
    }

    @Test
    fun `bastion S2 rushes through, connects, plunges on the spot and spends the charge`() {
        scenario("persona-bastion-s2") {
            val d = duel("bastion-greatsword")
            addStacks(d.caster, d.caster, charge, 4)
            val before = hp(d.target)
            val startZ = d.caster.position.z

            d.caster.rightClick()
            val peak = stepPeak(14)
            assertThat(d.caster.position.z).isGreaterThan(startZ + 2.0)
            val rush = before - hp(d.target)
            assertThat(rush).isGreaterThanOrEqualTo(7.0 + 0.5 * 4)
            assertThat(stacks(d.caster, charge)).isEqualTo(4)

            // plunge: 30 ticks after the rush connected, spends the charge
            val peak2 = stepPeak(40)
            val total = before - hp(d.target)
            assertThat(total).isGreaterThanOrEqualTo(rush + 9.0 + 0.5 * 4)
            assertThat(stacks(d.caster, charge)).isZero()
            assertThat(hitsOn(d.target)).isEqualTo(2)
            assertThat(maxOf(peak, peak2)).isLessThanOrEqualTo(60)

            stepPeak(40)
            assertDisplaysSpawnedAndGone()
        }
    }

    @Test
    fun `bastion S2 that connects with nobody only plays visuals`() {
        scenario("persona-bastion-s2-whiff") {
            val caster = player("caster", team = "red")
            caster.holds("bastion-greatsword")
            val bystander = player("bystander", at = Vec3(9.0, groundY, 0.0), team = "blue")
            fatten(bystander)
            val before = hp(bystander)

            caster.rightClick()
            stepPeak(80)

            assertThat(hp(bystander)).isEqualTo(before)
            assertDisplaysSpawnedAndGone()
        }
    }

    @Test
    fun `bastion S3 telegraphs, slams, aftershocks, spends charge and stays under the display cap`() {
        scenario("persona-bastion-s3") {
            val d = duel("bastion-greatsword")
            addStacks(d.caster, d.caster, charge, 5)
            val before = hp(d.target)

            d.caster.rightClick(sneaking = true)
            val telegraph = stepPeak(20)
            assertThat(before - hp(d.target)).isZero() // 29 ticks of windup: nothing has landed yet
            assertThat(sim.displays.active).isNotEmpty

            val peak = stepPeak(12) // tick 32: the slam
            val slam = before - hp(d.target)
            assertThat(slam).isGreaterThanOrEqualTo(10.0 + 0.7 * 5)
            assertThat(stacks(d.caster, charge)).isEqualTo(5)

            val peak2 = stepPeak(25) // tick 57: the aftershock
            assertThat(before - hp(d.target)).isGreaterThanOrEqualTo(slam + 7.0 + 0.7 * 5)
            assertThat(stacks(d.caster, charge)).isZero()
            assertThat(hitsOn(d.target)).isEqualTo(2)
            assertThat(maxOf(telegraph, peak, peak2)).isLessThanOrEqualTo(60)

            stepPeak(40)
            assertDisplaysSpawnedAndGone()
        }
    }

    @Test
    fun `bastion DEF raises resistances for the guard only, banks three charge and ends with a bash`() {
        scenario("persona-bastion-def") {
            val caster = player("caster", team = "red")
            val foe = player("foe", at = Vec3(0.0, groundY, 2.4), team = "blue")
            caster.holds("bastion-greatsword")
            caster.faces(foe)
            fatten(foe)

            caster.cast("bastion-greatsword-def")
            advance(2)
            assertThat(sim.displays.spawned).isNotEmpty
            assertThat(resist(caster, Attributes.RESIST_PHYSICAL)).isGreaterThanOrEqualTo(0.7)
            assertThat(resist(caster, Attributes.RESIST_KNOCKBACK)).isGreaterThanOrEqualTo(1.0)
            assertThat(stacks(caster, charge)).isEqualTo(1)

            val peak = stepPeak(22)
            assertThat(stacks(caster, charge)).isEqualTo(2)
            val peak2 = stepPeak(20) // tick 44: guard over
            assertThat(stacks(caster, charge)).isEqualTo(3)
            assertThat(resist(caster, Attributes.RESIST_PHYSICAL)).isZero()
            assertThat(resist(caster, Attributes.RESIST_KNOCKBACK)).isZero()
            assertThat(maxOf(peak, peak2)).isLessThanOrEqualTo(60)

            stepPeak(40)
            assertDisplaysSpawnedAndGone()
        }
    }

    @Test
    fun `both guards really cut damage taken`() {
        fun lossFrom(item: String, def: String?): Double {
            var loss = 0.0
            scenario("persona-guard-$item-${def != null}") {
                val victim = player("victim", team = "red")
                val attacker = player("attacker", at = Vec3(0.0, groundY, 2.2), team = "blue")
                victim.holds(item)
                attacker.holds("greatsword")
                attacker.faces(victim)
                fatten(victim)
                val before = hp(victim)
                if (def != null) {
                    victim.cast(def)
                    advance(2)
                }
                attacker.leftClick()
                advance(2)
                loss = before - hp(victim)
            }
            return loss
        }
        assertThat(lossFrom("bastion-greatsword", null)).isGreaterThanOrEqualTo(13.0)
        assertThat(lossFrom("bastion-greatsword", "bastion-greatsword-def")).isLessThan(13.0 * 0.5)
        assertThat(lossFrom("verdict-hammer", "verdict-hammer-def")).isLessThan(13.0 * 0.6)
    }

    @Test
    fun `verdict S3 collapse only judges targets still carrying stakes`() {
        scenario("persona-verdict-s3-unmarked") {
            val d = duel("verdict-hammer")
            d.caster.rightClick(sneaking = true)
            stepPeak(75)
            assertThat(hitsOn(d.target)).isEqualTo(1) // the eruption lands, the collapse skips an unmarked target
        }
    }

    @Test
    fun `bastion vfx use item, block and text displays`() {
        scenario("persona-bastion-kinds") {
            val d = duel("bastion-greatsword")
            d.caster.leftClick()
            stepPeak(30)
            val kinds = sim.displays.spawned.map { it.spec.kind }
            assertThat(kinds.any { it is DisplayKind.Item }).isTrue()
            assertThat(kinds.any { it is DisplayKind.Block }).isTrue()
            assertThat(kinds.any { it is DisplayKind.Text }).isTrue()
        }
    }

    // ===================================================================================
    // Verdict Hammer
    // ===================================================================================

    @Test
    fun `verdict S1 lands two beats and drives two stakes in`() {
        scenario("persona-verdict-s1") {
            val d = duel("verdict-hammer")
            val before = hp(d.target)

            d.caster.leftClick()
            advance(1)
            assertThat(before - hp(d.target)).isGreaterThanOrEqualTo(7.0)
            assertThat(stacks(d.target, rupture)).isEqualTo(1)
            assertThat(sim.displays.spawned).isNotEmpty

            val peak = stepPeak(25)
            // echo: 7 + 0.5 per stake (1 stake read before its own stake lands)
            assertThat(before - hp(d.target)).isGreaterThanOrEqualTo(7.0 + 8.5)
            assertThat(stacks(d.target, rupture)).isEqualTo(2)
            assertThat(hitsOn(d.target)).isEqualTo(2)
            assertThat(peak).isLessThanOrEqualTo(60)

            stepPeak(40)
            assertDisplaysSpawnedAndGone()
        }
    }

    @Test
    fun `verdict S2 dashes in, marks the spot, drops the stakes 38 ticks later`() {
        scenario("persona-verdict-s2") {
            val d = duel("verdict-hammer")
            val before = hp(d.target)
            val startZ = d.caster.position.z

            d.caster.rightClick()
            val peak = stepPeak(14)
            assertThat(d.caster.position.z).isGreaterThan(startZ + 2.0)
            val dash = before - hp(d.target)
            assertThat(dash).isGreaterThanOrEqualTo(7.0)
            assertThat(stacks(d.target, rupture)).isEqualTo(1)
            // the V decal is a text display
            assertThat(sim.displays.spawned.map { it.spec.kind }.filterIsInstance<DisplayKind.Text>()).isNotEmpty

            // before the drop lands the target can still walk out of the hoop
            val peak2 = stepPeak(40)
            assertThat(before - hp(d.target)).isGreaterThanOrEqualTo(dash + 9.0 + 0.8)
            assertThat(stacks(d.target, rupture)).isEqualTo(3)
            assertThat(hitsOn(d.target)).isEqualTo(2)
            assertThat(maxOf(peak, peak2)).isLessThanOrEqualTo(60)

            stepPeak(60)
            assertDisplaysSpawnedAndGone()
        }
    }

    @Test
    fun `verdict S2 stakes miss a target that leaves the hoop`() {
        scenario("persona-verdict-s2-dodge") {
            val d = duel("verdict-hammer")
            val before = hp(d.target)

            d.caster.rightClick()
            stepPeak(14)
            val dash = before - hp(d.target)
            d.target.position = Vec3(12.0, groundY, 3.0) // out of the 3.3-block hoop
            stepPeak(40)

            assertThat(before - hp(d.target)).isEqualTo(dash)
            assertThat(stacks(d.target, rupture)).isEqualTo(1)
        }
    }

    @Test
    fun `verdict S3 scales with stakes, collapses and spends them, under the display cap`() {
        scenario("persona-verdict-s3") {
            val d = duel("verdict-hammer")
            addStacks(d.caster, d.target, rupture, 4)
            val before = hp(d.target)

            d.caster.rightClick(sneaking = true)
            val telegraph = stepPeak(30)
            assertThat(before - hp(d.target)).isZero() // 39 ticks of windup
            assertThat(sim.displays.active).isNotEmpty

            val peak = stepPeak(14) // tick 44: the eruption
            val eruption = before - hp(d.target)
            assertThat(eruption).isGreaterThanOrEqualTo(8.0 + 1.0 * 4)
            assertThat(stacks(d.target, rupture)).isEqualTo(4)

            val peak2 = stepPeak(24) // tick 68: the collapse
            assertThat(before - hp(d.target)).isGreaterThanOrEqualTo(eruption + 6.0 + 1.0 * 4)
            assertThat(stacks(d.target, rupture)).isZero()
            assertThat(hitsOn(d.target)).isEqualTo(2)
            assertThat(maxOf(telegraph, peak, peak2)).isLessThanOrEqualTo(60)

            stepPeak(40)
            assertDisplaysSpawnedAndGone()
        }
    }

    @Test
    fun `verdict DEF raises resistances for the guard only, pulses a counter and plants a stake`() {
        scenario("persona-verdict-def") {
            val d = duel("verdict-hammer", targetZ = 2.5)
            val before = hp(d.target)

            d.caster.cast("verdict-hammer-def")
            advance(2)
            assertThat(resist(d.caster, Attributes.RESIST_PHYSICAL)).isGreaterThanOrEqualTo(0.6)
            assertThat(resist(d.caster, Attributes.RESIST_KNOCKBACK)).isGreaterThanOrEqualTo(1.0)
            assertThat(before - hp(d.target)).isZero() // the counter waits for the guard to release
            val peak = stepPeak(28)

            assertThat(resist(d.caster, Attributes.RESIST_PHYSICAL)).isZero()
            assertThat(before - hp(d.target)).isGreaterThanOrEqualTo(4.0)
            assertThat(stacks(d.target, rupture)).isEqualTo(1)
            assertThat(peak).isLessThanOrEqualTo(60)

            stepPeak(40)
            assertDisplaysSpawnedAndGone()
        }
    }

    @Test
    fun `verdict vfx use item, block and text displays`() {
        scenario("persona-verdict-kinds") {
            val d = duel("verdict-hammer")
            d.caster.leftClick()
            stepPeak(30)
            val kinds = sim.displays.spawned.map { it.spec.kind }
            assertThat(kinds.any { it is DisplayKind.Item }).isTrue()
            assertThat(kinds.any { it is DisplayKind.Block }).isTrue()
            assertThat(kinds.any { it is DisplayKind.Text }).isTrue()
        }
    }
}
