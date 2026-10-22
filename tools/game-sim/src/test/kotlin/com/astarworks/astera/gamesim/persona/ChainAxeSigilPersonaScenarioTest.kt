package com.astarworks.astera.gamesim.persona

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.core.application.services.getOrThrow
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
 * ADR-0221 persona weapons #7 (`crimson-chain-axe`, Rupture) and #8 (`sigil-rifle`, Sinking): every
 * binding casts, offensive skills damage a dummy, stacks apply / are consumed as designed, displays
 * spawn within the 60-display budget and are all gone by the end of the effect.
 */
class ChainAxeSigilPersonaScenarioTest {

    private val rupture = StatusEffectId("rupture")
    private val sinking = StatusEffectId("sinking")

    private fun ScenarioScope.stacks(entity: SimEntity, id: StatusEffectId): Int =
        sim.services.getOrThrow<IStatusEffectContainer>().stacks(entity.id, id)

    private fun ScenarioScope.give(caster: SimEntity, target: SimEntity, id: StatusEffectId, n: Int) {
        val dispatcher = checkNotNull(sim.combat.mechanicDispatcher) { "dispatcher not wired" }
        dispatcher.dispatch(
            listOf(Mechanic.ApplyStatus(Targeter.Target, id, IntValue.Literal(400), stackCount = IntValue.Literal(n))),
            MechanicContext(
                actorId = caster.id,
                targetId = target.id,
                origin = caster.position,
                worldId = caster.world,
                pose = PlayerPose(caster.world, caster.position, caster.eyePosition, caster.direction),
            ),
        )
    }

    /** Advances [ticks] one at a time, returning the highest number of simultaneously live displays. */
    private fun ScenarioScope.runFor(ticks: Int): Int {
        var peak = sim.displays.active.size
        repeat(ticks) {
            advance(1)
            peak = maxOf(peak, sim.displays.active.size)
        }
        return peak
    }

    /** Distinct ticks on which [victim] lost HP while advancing [ticks]: one per landed damage beat. */
    private fun ScenarioScope.countHits(victim: SimEntity, ticks: Int, from: Double = hp(victim)): Pair<Int, Int> {
        var peak = sim.displays.active.size
        var hits = 0
        var last = from
        repeat(ticks) {
            advance(1)
            peak = maxOf(peak, sim.displays.active.size)
            val now = hp(victim)
            if (now < last) hits++
            last = now
        }
        return peak to hits
    }

    private fun ScenarioScope.assertDisplaysClean(peak: Int) {
        assertThat(sim.displays.spawned).`as`("displays spawned").isNotEmpty()
        assertThat(peak).`as`("peak live displays").isLessThanOrEqualTo(60)
        assertThat(sim.displays.active).`as`("displays left behind").isEmpty()
    }

    private fun pair(sc: ScenarioScope, distance: Double): Pair<SimEntity, SimEntity> = with(sc) {
        val caster = player("caster", team = "red")
        val victim = player("victim", at = Vec3(0.0, groundY, distance), team = "blue")
        caster.faces(victim)
        caster to victim
    }

    // ---- Crimson Chain-Axe -------------------------------------------------------------------

    @Test
    fun `axe S1 chain sweep lands three beats and stacks rupture`() {
        scenario("persona-axe-s1") {
            val (caster, victim) = pair(this, 2.5)
            val before = hp(victim)

            caster.cast("crimson-chain-axe-s1")
            val (peak, hits) = countHits(victim, 50, before)
            assertThat(hits).`as`("damage beats landed").isEqualTo(3)

            assertThat(before - hp(victim)).isGreaterThan(0.0)
            assertThat(stacks(victim, rupture)).isEqualTo(3)
            // three ribbon passes + chain links + the closing star, plus three impact markers
            assertThat(sim.displays.spawned.map { it.spec.kind }.filterIsInstance<DisplayKind.Item>()).isNotEmpty()
            assertThat(sim.displays.spawned.map { it.spec.kind }.filterIsInstance<DisplayKind.Text>()).hasSizeGreaterThanOrEqualTo(4)
            assertDisplaysClean(peak)
        }
    }

    @Test
    fun `axe S2 hooked lunge dashes forward and chains three hits`() {
        scenario("persona-axe-s2") {
            val (caster, victim) = pair(this, 5.0)
            val before = hp(victim)

            caster.cast("crimson-chain-axe-s2")
            val (peak, hits) = countHits(victim, 50, before)
            assertThat(hits).`as`("damage beats landed").isEqualTo(3)

            assertThat(caster.position.z).isGreaterThan(2.0)
            assertThat(before - hp(victim)).isGreaterThan(0.0)
            assertThat(stacks(victim, rupture)).isEqualTo(3)
            assertDisplaysClean(peak)
        }
    }

    @Test
    fun `axe S3 blade forest spends rupture for extra damage and clears its displays`() {
        fun loss(preloaded: Int): Pair<Double, Int> {
            var lost = 0.0
            var left = -1
            scenario("persona-axe-s3-$preloaded") {
                val (caster, victim) = pair(this, 5.0)
                if (preloaded > 0) give(caster, victim, rupture, preloaded)
                val before = hp(victim)

                caster.cast("crimson-chain-axe-s3")
                advance(1)
                assertThat(caster.frozenTicks).`as`("caster planted during the telegraph").isGreaterThan(0)
                val (peak, hits) = countHits(victim, 110, before)
                assertThat(hits).`as`("forest blow + finishing slash").isEqualTo(2)

                lost = before - hp(victim)
                left = stacks(victim, rupture)
                assertDisplaysClean(peak)
            }
            return lost to left
        }

        val (plain, plainLeft) = loss(0)
        val (stacked, stackedLeft) = loss(4)
        assertThat(plain).isGreaterThan(0.0)
        assertThat(stacked).`as`("four rupture stacks add 4 x 1.5 on the forest blow").isGreaterThan(plain)
        assertThat(plainLeft).isZero()
        assertThat(stackedLeft).`as`("forest consumes every stack").isZero()
    }

    @Test
    fun `axe DEF chain ward pushes close enemies away and marks them`() {
        scenario("persona-axe-def") {
            val (caster, victim) = pair(this, 2.0)

            caster.cast("crimson-chain-axe-def")
            val peak = runFor(30)

            assertThat(stacks(victim, rupture)).isEqualTo(1)
            assertDisplaysClean(peak)
        }
    }

    // ---- Sigil Rifle -------------------------------------------------------------------------

    @Test
    fun `rifle S1 marked round pierces to a far target and leaves a sinking sigil`() {
        scenario("persona-rifle-s1") {
            val (caster, victim) = pair(this, 14.0)
            val before = hp(victim)

            caster.cast("sigil-rifle-s1")
            val (peak, hits) = countHits(victim, 45, before)
            assertThat(hits).isEqualTo(1)

            assertThat(before - hp(victim)).isGreaterThan(0.0)
            assertThat(stacks(victim, sinking)).isEqualTo(1)
            assertThat(sim.displays.spawned.map { it.spec.kind }.filterIsInstance<DisplayKind.Block>().map { it.blockKey })
                .contains("white_concrete", "red_stained_glass")
            assertDisplaysClean(peak)
        }
    }

    @Test
    fun `rifle S2 dash-cut slides forward and cuts twice`() {
        scenario("persona-rifle-s2") {
            val (caster, victim) = pair(this, 4.0)
            val before = hp(victim)

            caster.cast("sigil-rifle-s2")
            val (peak, hits) = countHits(victim, 45, before)
            assertThat(hits).`as`("two cuts").isEqualTo(2)

            assertThat(caster.position.z).isGreaterThan(1.5)
            assertThat(before - hp(victim)).isGreaterThan(0.0)
            assertThat(stacks(victim, sinking)).isEqualTo(2)
            assertDisplaysClean(peak)
        }
    }

    @Test
    fun `rifle S3 detonation stacks sigils then pops them for stack-scaled damage`() {
        fun run(preloaded: Int): Pair<Double, Int> {
            var lost = 0.0
            var left = -1
            scenario("persona-rifle-s3-$preloaded") {
                val (caster, victim) = pair(this, 12.0)
                if (preloaded > 0) give(caster, victim, sinking, preloaded)
                val before = hp(victim)

                caster.cast("sigil-rifle-s3")
                advance(1)
                assertThat(caster.frozenTicks).`as`("caster rooted while charging").isGreaterThan(0)
                val (peakA, beamHits) = countHits(victim, 46, before) // tick 47: beam landed (44), sigils stacking (46)
                assertThat(beamHits).`as`("beam beat").isEqualTo(1)
                assertThat(stacks(victim, sinking)).`as`("beam adds two sigils").isEqualTo(preloaded + 2)
                val (peakB, popHits) = countHits(victim, 60, hp(victim))
                assertThat(popHits).`as`("detonation beat").isEqualTo(1)
                val peak = maxOf(peakA, peakB)

                lost = before - hp(victim)
                left = stacks(victim, sinking)
                assertDisplaysClean(peak)
            }
            return lost to left
        }

        val (plain, plainLeft) = run(0)
        val (stacked, stackedLeft) = run(1)
        assertThat(plain).isGreaterThan(0.0)
        assertThat(stacked).`as`("one extra sigil adds 1.5 on the pop").isGreaterThan(plain)
        assertThat(plainLeft).isZero()
        assertThat(stackedLeft).isZero()
    }

    @Test
    fun `rifle DEF sigil ward steps back and marks close enemies`() {
        scenario("persona-rifle-def") {
            val (caster, victim) = pair(this, 2.0)

            caster.cast("sigil-rifle-def")
            val peak = runFor(30)

            assertThat(caster.velocity.z).`as`("pushed backward").isLessThan(0.0)
            assertThat(stacks(victim, sinking)).isEqualTo(1)
            assertDisplaysClean(peak)
        }
    }
}
