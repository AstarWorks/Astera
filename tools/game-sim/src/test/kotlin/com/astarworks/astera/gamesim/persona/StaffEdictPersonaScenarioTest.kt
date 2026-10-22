package com.astarworks.astera.gamesim.persona

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.core.adapter.api.event.IMcEvent
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.gamesim.ScenarioScope
import com.astarworks.astera.gamesim.SimEntity
import com.astarworks.astera.gamesim.scenario
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Persona weapons #9 `orrery-staff` (charge) and #10 `edict-blade` (poise), ADR-0221.
 *
 * Every skill is fired through its real item binding (left / right / sneak + right / sneak + left), then the
 * test checks: damage lands on a dummy where the skill is offensive, the stack the design promises is
 * banked / spent, displays are spawned and all of them are removed by the end of the effect, and the live
 * display count stays inside the per-skill budget (60).
 *
 * Note: players carry 20-tick invulnerability frames, and `damage.ignore_invulnerability` is not yet honoured
 * by the dispatcher, so only the first hit of a multi-hit skill lands in the sim. The tests therefore assert
 * "damage landed" (and relative scaling for the first hit) rather than the full per-skill damage budget.
 */
class StaffEdictPersonaScenarioTest {

    private val charge = StatusEffectId("charge")
    private val poise = StatusEffectId("poise")
    private val rupture = StatusEffectId("rupture")
    private val evasive = StatusEffectId("evasive")

    // ---- helpers -----------------------------------------------------------------------------

    private data class Duel(val caster: SimEntity, val target: SimEntity)

    /** Caster at [x]/0 facing +z holding [item]; a blue dummy [distance] blocks ahead. */
    private fun ScenarioScope.duel(item: String, distance: Double, x: Double = 0.0): Duel {
        val caster = player("caster-$x", at = Vec3(x, groundY, 0.0), team = "red")
        val target = player("target-$x", at = Vec3(x, groundY, distance), team = "blue")
        caster.holds(item)
        caster.faces(target)
        return Duel(caster, target)
    }

    private fun ScenarioScope.stacks(entity: SimEntity, id: StatusEffectId): Int =
        sim.services.getOrThrow<IStatusEffectContainer>().stacks(entity.id, id)

    private fun ScenarioScope.seed(caster: SimEntity, id: StatusEffectId, n: Int) {
        val dispatcher = checkNotNull(sim.combat.mechanicDispatcher) { "dispatcher not wired" }
        dispatcher.dispatch(
            listOf(Mechanic.ApplyStatus(Targeter.Caster, id, IntValue.Literal(400), stackCount = IntValue.Literal(n))),
            MechanicContext(
                actorId = caster.id,
                targetId = null,
                origin = caster.position,
                worldId = caster.world,
                pose = PlayerPose(caster.world, caster.position, caster.eyePosition, caster.direction),
            ),
        )
        assertThat(stacks(caster, id)).isEqualTo(n)
    }

    private fun ScenarioScope.sneakLeft(entity: SimEntity) {
        assertThat(sim.input(entity, IMcEvent.InputKind.LEFT_CLICK, sneaking = true)).isTrue()
    }

    private fun ScenarioScope.sneakRight(entity: SimEntity) {
        assertThat(sim.input(entity, IMcEvent.InputKind.RIGHT_CLICK, sneaking = true)).isTrue()
    }

    /** Advances [ticks] one at a time and returns the highest number of live displays seen. */
    private fun ScenarioScope.runTracking(ticks: Int): Int {
        var peak = sim.displays.active.size
        repeat(ticks) {
            advance(1)
            peak = maxOf(peak, sim.displays.active.size)
        }
        return peak
    }

    private fun ScenarioScope.assertDisplaysAllRemoved() {
        assertThat(sim.displays.active).`as`("displays still alive").isEmpty()
        assertThat(sim.recorder.effects("display_remove")).hasSameSizeAs(sim.recorder.effects("display_spawn"))
    }

    private fun ScenarioScope.blocks(): List<String> =
        sim.displays.spawned.map { it.spec.kind }.filterIsInstance<DisplayKind.Block>().map { it.blockKey.removePrefix("minecraft:") }

    private fun ScenarioScope.texts(): List<String> =
        sim.displays.spawned.filter { it.spec.kind is DisplayKind.Text }.flatMap { it.lines }

    // ---- orrery-staff ------------------------------------------------------------------------

    @Test
    fun `orrery s1 twin arcs hit, bank two charge and draw gold crescents`() {
        scenario("persona-orrery-s1") {
            val (caster, target) = duel("orrery-staff", 3.0)
            val before = hp(target)

            caster.leftClick()
            val peak = runTracking(30)

            assertThat(hp(target)).isLessThan(before)
            assertThat(stacks(caster, charge)).isEqualTo(2)
            assertThat(blocks()).contains("gold_block", "orange_stained_glass", "lime_concrete", "red_concrete")
            assertThat(texts().joinToString()).contains("✦").contains("○")
            assertThat(peak).isBetween(10, 60)
            advance(60)
            assertDisplaysAllRemoved()
        }
    }

    @Test
    fun `orrery s2 clockwork spin draws the seal, finishes with a flash and hit-stop`() {
        scenario("persona-orrery-s2") {
            val (caster, target) = duel("orrery-staff", 3.0)
            val before = hp(target)

            caster.rightClick()
            val peak = runTracking(30)

            assertThat(hp(target)).isLessThan(before)
            assertThat(stacks(caster, charge)).isEqualTo(3)
            assertThat(target.frozenTicks).`as`("finisher hit-stop").isGreaterThan(0)
            assertThat(sim.recorder.effects("camera_shake")).isNotEmpty()
            assertThat(texts().joinToString()).contains("☸").contains("✺").contains("✦")
            assertThat(blocks()).contains("gold_block", "orange_concrete", "white_concrete")
            assertThat(peak).isBetween(20, 60)
            advance(60)
            assertDisplaysAllRemoved()
        }
    }

    @Test
    fun `orrery s3 beam litany pierces both targets in line, scales with charge and spends it`() {
        scenario("persona-orrery-s3") {
            val charged = duel("orrery-staff", 6.0, x = -12.0)
            val plain = duel("orrery-staff", 6.0, x = 12.0)
            // A second dummy behind the first in the charged lane: the beam pierces.
            val behind = player("behind", at = Vec3(-12.0, groundY, 11.0), team = "blue")
            seed(charged.caster, charge, 8)
            val chargedBefore = hp(charged.target)
            val plainBefore = hp(plain.target)
            val behindBefore = hp(behind)

            sneakRight(charged.caster)
            sneakRight(plain.caster)
            val peak = runTracking(60)

            val chargedLoss = chargedBefore - hp(charged.target)
            val plainLoss = plainBefore - hp(plain.target)
            assertThat(plainLoss).isGreaterThan(0.0)
            assertThat(chargedLoss).`as`("8 Charge adds power").isGreaterThan(plainLoss)
            assertThat(hp(behind)).`as`("beam pierces").isLessThan(behindBefore)
            assertThat(stacks(charged.caster, charge)).`as`("bank spent").isZero()
            assertThat(blocks()).contains("glowstone", "white_concrete", "yellow_stained_glass", "orange_stained_glass")
            assertThat(texts().joinToString()).contains("☸")
            assertThat(peak).`as`("two casters share the sim").isGreaterThan(30)
            advance(80)
            assertDisplaysAllRemoved()
        }
    }

    @Test
    fun `orrery s3 alone stays inside the per-skill display budget`() {
        scenario("persona-orrery-s3-budget") {
            val (caster, target) = duel("orrery-staff", 6.0)
            seed(caster, charge, 4)
            sneakRight(caster)
            val peak = runTracking(70)
            assertThat(peak).isBetween(30, 60)
            assertThat(hp(target)).isLessThan(20.0)
            advance(60)
            assertDisplaysAllRemoved()
        }
    }

    @Test
    fun `orrery def counter-seal grants evade and charge, singes what is close`() {
        scenario("persona-orrery-def") {
            val (caster, target) = duel("orrery-staff", 2.5)
            val before = hp(target)

            sneakLeft(caster)
            val peak = runTracking(30)

            assertThat(sim.services.getOrThrow<IStatusEffectContainer>().has(caster.id, evasive)).isTrue()
            assertThat(stacks(caster, charge)).isEqualTo(2)
            assertThat(hp(target)).isLessThan(before)
            assertThat(target.frozenTicks).isGreaterThan(0)
            assertThat(blocks()).contains("gold_block", "orange_concrete")
            assertThat(peak).isBetween(20, 60)
            advance(40)
            assertDisplaysAllRemoved()
        }
    }

    // ---- edict-blade -------------------------------------------------------------------------

    @Test
    fun `edict s1 dash-cut passes through the target, cuts it and banks three poise`() {
        scenario("persona-edict-s1") {
            val (caster, target) = duel("edict-blade", 3.0)
            val before = hp(target)

            caster.leftClick()
            val peak = runTracking(26)

            assertThat(hp(target)).isLessThan(before)
            assertThat(caster.position.z).`as`("dashed through").isGreaterThan(target.position.z)
            assertThat(stacks(caster, poise)).isEqualTo(3)
            assertThat(target.frozenTicks).isGreaterThan(0)
            assertThat(blocks()).contains("white_concrete", "black_concrete")
            assertThat(texts().joinToString()).contains("★").contains("✧").contains("✦")
            assertThat(peak).isBetween(10, 60)
            advance(40)
            assertDisplaysAllRemoved()
        }
    }

    @Test
    fun `edict s2 sky rend dives onto the target, marks it with rupture and a following star`() {
        scenario("persona-edict-s2") {
            val (caster, target) = duel("edict-blade", 4.0)
            val before = hp(target)

            caster.rightClick()
            val peak = runTracking(40)

            assertThat(hp(target)).isLessThan(before)
            assertThat(stacks(target, rupture)).`as`("mark").isEqualTo(2)
            assertThat(stacks(caster, poise)).isEqualTo(3)
            assertThat(blocks()).contains("quartz_block", "black_concrete")
            assertThat(sim.displays.active.values.flatMap { it.lines }.joinToString()).`as`("mark star still follows").contains("★")
            assertThat(sim.recorder.effects("camera_shake")).isNotEmpty()
            assertThat(peak).isBetween(15, 60)
            advance(140)
            assertDisplaysAllRemoved()
        }
    }

    @Test
    fun `edict s3 final prescript garbles then resolves, claws the cone and spends poise and rupture`() {
        scenario("persona-edict-s3") {
            val (caster, target) = duel("edict-blade", 6.0)
            val before = hp(target)
            seed(caster, poise, 5)

            sneakRight(caster)
            val peak = runTracking(70)

            assertThat(hp(target)).isLessThan(before)
            assertThat(stacks(caster, poise)).`as`("poise spent").isZero()
            assertThat(stacks(target, rupture)).`as`("rupture spent by the finisher").isZero()
            val textSwaps = sim.displays.swaps.filter { it.kind == "text" }.map { it.value }
            assertThat(textSwaps.size).`as`("one random glyph string per tick").isGreaterThanOrEqualTo(10)
            assertThat(textSwaps.distinct().size).isGreaterThanOrEqualTo(8)
            assertThat(textSwaps.last { it.contains("斬") }).isEqualTo("<#ffffff><b>斬</b>")
            assertThat(blocks()).contains("black_concrete", "light_blue_concrete", "white_concrete")
            assertThat(sim.displays.swaps.filter { it.kind == "block" }.map { it.value }).contains("light_gray_stained_glass")
            assertThat(sim.recorder.effects("camera_shake")).isNotEmpty()
            assertThat(peak).isBetween(30, 60)
            advance(80)
            assertDisplaysAllRemoved()
        }
    }

    @Test
    fun `edict def cold reply backsteps, banks poise and counters the cone`() {
        scenario("persona-edict-def") {
            val (caster, target) = duel("edict-blade", 3.5)
            val before = hp(target)

            sneakLeft(caster)
            val peak = runTracking(26)

            assertThat(sim.services.getOrThrow<IStatusEffectContainer>().has(caster.id, evasive)).isTrue()
            assertThat(stacks(caster, poise)).isEqualTo(2)
            assertThat(hp(target)).isLessThan(before)
            assertThat(blocks()).contains("white_stained_glass", "white_concrete")
            assertThat(peak).isBetween(4, 60)
            advance(40)
            assertDisplaysAllRemoved()
        }
    }
}
