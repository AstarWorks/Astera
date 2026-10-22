package com.astarworks.astera.gamesim.persona

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.core.adapter.api.event.IMcEvent
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
 * ADR-0221 persona weapons #1 (`couture-rapier`, poise) and #2 (`gossamer-katana`, bleed): every
 * bound skill casts through the real item bindings, deals its designed damage to a dummy, builds or
 * spends its stack, spawns its layered displays within the live-display budget and leaves no display
 * behind. The dummy gets 200 HP so a whole kit's damage stays measurable (a crit only ever adds).
 * Damage is lumped per 20-tick invulnerability window: `damage.ignore_invulnerability` is not honoured yet.
 */
class RapierKatanaPersonaScenarioTest {

    private val poise = StatusEffectId("poise")
    private val bleed = StatusEffectId("bleed")

    /** Per-skill budget from the persona roster: at most this many displays alive at once. */
    private val liveDisplayBudget = 60

    private data class Duel(val caster: SimEntity, val target: SimEntity)

    private fun ScenarioScope.duel(weapon: String, distance: Double = 3.0): Duel {
        val caster = player("caster", team = "red")
        val target = player("target", at = Vec3(0.0, groundY, distance), team = "blue")
        sim.setMaxHealth(target, DUMMY_HP)
        caster.holds(weapon)
        caster.faces(target)
        return Duel(caster, target)
    }

    private fun ScenarioScope.lost(target: SimEntity): Double = DUMMY_HP - hp(target)

    private fun ScenarioScope.stacks(entity: SimEntity, id: StatusEffectId): Int =
        sim.services.getOrThrow<IStatusEffectContainer>().stacks(entity.id, id)

    /** Adds [n] stacks of [id] to [entity] through the real mechanic dispatcher (the same path a skill uses). */
    private fun ScenarioScope.seed(entity: SimEntity, id: StatusEffectId, n: Int) {
        val dispatcher = checkNotNull(sim.combat.mechanicDispatcher) { "dispatcher not wired" }
        dispatcher.dispatch(
            listOf(Mechanic.ApplyStatus(Targeter.Caster, id, IntValue.Literal(400), stackCount = IntValue.Literal(n))),
            MechanicContext(
                actorId = entity.id,
                targetId = null,
                origin = entity.position,
                worldId = entity.world,
                pose = PlayerPose(entity.world, entity.position, entity.eyePosition, entity.direction),
            ),
        )
    }

    /** Steps [ticks] one at a time and returns the most displays alive at any tick. */
    private fun ScenarioScope.peakDisplays(ticks: Int): Int {
        var peak = sim.displays.active.size
        repeat(ticks) {
            advance(1)
            peak = maxOf(peak, sim.displays.active.size)
        }
        return peak
    }

    private fun ScenarioScope.assertAllDisplaysGone() {
        advance(60)
        assertThat(sim.displays.active).isEmpty()
        assertThat(sim.recorder.effects("display_remove")).hasSameSizeAs(sim.recorder.effects("display_spawn"))
    }

    private fun ScenarioScope.spawnedBlocks(block: String): Int =
        sim.displays.spawned.count { (it.spec.kind as? DisplayKind.Block)?.blockKey == block }

    private fun ScenarioScope.spawnedGlyphs(glyph: String): Int =
        sim.displays.spawned.count { it.spec.kind is DisplayKind.Text && it.lines.any { line -> glyph in line } }

    private fun ScenarioScope.sneakLeftClick(who: SimEntity) {
        sim.input(who, IMcEvent.InputKind.LEFT_CLICK, sneaking = true)
    }

    private fun ScenarioScope.sneakRightClick(who: SimEntity) {
        sim.input(who, IMcEvent.InputKind.RIGHT_CLICK, sneaking = true)
    }

    // ---------------------------------------------------------------- couture-rapier

    @Test
    fun `rapier S1 stitches three beats, banks three poise and cleans up`() {
        scenario("persona-rapier-s1") {
            val (caster, target) = duel("couture-rapier")
            caster.leftClick()
            val hit1 = lost(target)
            assertThat(hit1).isGreaterThan(0.0)
            assertThat(stacks(caster, poise)).isEqualTo(1)

            val peak = peakDisplays(14)
            // one 7.5 lump on beat 1; beats 2 and 3 (3 and 6 ticks later) are poise + feel only
            assertThat(lost(target)).isBetween(7.4, 20.0)
            assertThat(stacks(caster, poise)).isEqualTo(3)
            assertThat(target.frozenTicks).isGreaterThanOrEqualTo(0)
            // three needle thrusts (shaft + core + glyph each) and three impact flowers (full 6 + 8 + 1,
            // lite 4 + 4 + 1, full again)
            assertThat(sim.displays.spawned.size).isGreaterThanOrEqualTo(3 * 3 + 15 + 9 + 15)
            assertThat(spawnedBlocks("cyan_stained_glass")).isGreaterThan(0)
            assertThat(peak).isLessThanOrEqualTo(liveDisplayBudget)
            assertAllDisplaysGone()
        }
    }

    @Test
    fun `rapier S2 dashes through, lands the scissor cross and the flicker, and banks poise`() {
        scenario("persona-rapier-s2") {
            val (caster, target) = duel("couture-rapier")
            val start = caster.position
            caster.rightClick()

            // 3-tick wind-up: nothing has moved or been hurt yet, the afterimages are already up
            assertThat(caster.position).isEqualTo(start)
            assertThat(lost(target)).isZero()
            assertThat(sim.displays.spawned.any { it.spec.kind is DisplayKind.Item }).isTrue()

            val peak = peakDisplays(34)
            assertThat(caster.position.z).isGreaterThan(5.0)
            // 6.0 scissor cut + 4.0 closing thrust 21 ticks later (the flicker between is feel only)
            assertThat(lost(target)).isBetween(9.9, 25.0)
            assertThat(stacks(caster, poise)).isEqualTo(5)
            assertThat(spawnedGlyphs("✦")).isGreaterThan(0)
            assertThat(sim.displays.spawned.any { (it.spec.kind as? DisplayKind.Item)?.itemId?.value == "prismarine-shard" }).isTrue()
            assertThat(peak).isLessThanOrEqualTo(liveDisplayBudget)
            assertAllDisplaysGone()
        }
    }

    @Test
    fun `rapier S3 telegraphs, then spends poise on a heavy thrust, flurry and gold dome`() {
        scenario("persona-rapier-s3-charged") {
            val (caster, target) = duel("couture-rapier")
            seed(caster, poise, 5)
            sneakRightClick(caster)

            // the 12-tick charge: the lane is outlined and nothing has been hurt
            assertThat(caster.frozenTicks).isGreaterThan(0)
            var peak = peakDisplays(11)
            assertThat(target.glowing).isTrue()
            assertThat(lost(target)).isZero()
            assertThat(spawnedGlyphs("✦")).isGreaterThan(0)

            peak = maxOf(peak, peakDisplays(60))
            // (6.0 + 5 x 0.3) heavy thrust + (6.0 + 5 x 0.5) finisher
            assertThat(lost(target)).isBetween(15.9, 40.0)
            assertThat(stacks(caster, poise)).isZero()
            assertThat(spawnedGlyphs("突")).isEqualTo(1)
            assertThat(spawnedBlocks("yellow_stained_glass")).isGreaterThanOrEqualTo(24)
            assertThat(peak).isLessThanOrEqualTo(liveDisplayBudget)
            assertAllDisplaysGone()
        }
    }

    @Test
    fun `rapier S3 without enough poise still hits but skips the gold dome`() {
        scenario("persona-rapier-s3-dry") {
            val (caster, target) = duel("couture-rapier")
            seed(caster, poise, 1)
            sneakRightClick(caster)
            val peak = peakDisplays(70)

            // (6.0 + 0.3) + (6.0 + 0.5) with a single poise
            assertThat(lost(target)).isBetween(12.7, 40.0)
            assertThat(stacks(caster, poise)).isZero()
            assertThat(spawnedBlocks("yellow_stained_glass")).isZero()
            assertThat(spawnedGlyphs("突")).isEqualTo(1)
            assertThat(peak).isLessThanOrEqualTo(liveDisplayBudget)
            assertAllDisplaysGone()
        }
    }

    @Test
    fun `rapier S3 into empty air only whiffs`() {
        scenario("persona-rapier-s3-whiff") {
            val (caster, target) = duel("couture-rapier", distance = 3.0)
            target.position = Vec3(20.0, groundY, 3.0)
            seed(caster, poise, 5)
            sneakRightClick(caster)
            advance(70)

            assertThat(lost(target)).isZero()
            // nothing was struck, so no poise is spent and no impact frame or dome is shown
            assertThat(stacks(caster, poise)).isEqualTo(5)
            assertThat(spawnedGlyphs("突")).isZero()
            assertThat(spawnedBlocks("yellow_stained_glass")).isZero()
            assertAllDisplaysGone()
        }
    }

    @Test
    fun `rapier DEF hops back, banks two poise and leaves a guard`() {
        scenario("persona-rapier-def") {
            val (caster, _) = duel("couture-rapier")
            sneakLeftClick(caster)
            assertThat(caster.velocity.z).isLessThan(0.0)
            assertThat(stacks(caster, poise)).isEqualTo(2)
            val peak = peakDisplays(20)
            assertThat(spawnedBlocks("white_concrete")).isGreaterThanOrEqualTo(7)
            assertThat(peak).isLessThanOrEqualTo(liveDisplayBudget)
            assertAllDisplaysGone()
        }
    }

    // ---------------------------------------------------------------- gossamer-katana

    @Test
    fun `katana S1 cuts twice, the second beat leaves one bleed stack`() {
        scenario("persona-katana-s1") {
            val (caster, target) = duel("gossamer-katana")
            caster.leftClick()
            assertThat(lost(target)).isGreaterThan(0.0)
            assertThat(stacks(target, bleed)).isZero()

            val peak = peakDisplays(14)
            // one 7.0 lump; beat 2 adds the bleed
            assertThat(lost(target)).isBetween(6.9, 20.0)
            assertThat(stacks(target, bleed)).isEqualTo(1)
            assertThat(spawnedBlocks("light_blue_stained_glass")).isGreaterThan(0)
            assertThat(spawnedBlocks("blue_stained_glass")).isGreaterThan(0)
            assertThat(peak).isLessThanOrEqualTo(liveDisplayBudget)
            assertAllDisplaysGone()
        }
    }

    @Test
    fun `katana S2 is a delayed line -- no damage for 20 ticks, then all five hits and two bleed`() {
        scenario("persona-katana-s2") {
            val (caster, target) = duel("gossamer-katana", distance = 4.0)
            caster.rightClick()

            // the draw: rooted, the line is up, the lane is outlined
            assertThat(caster.frozenTicks).isGreaterThan(0)
            var peak = peakDisplays(19)
            assertThat(target.glowing).isTrue()
            assertThat(lost(target)).isZero()
            assertThat(stacks(target, bleed)).isZero()
            assertThat(spawnedBlocks("white_concrete")).isZero() // the white flash has not fired yet

            peak = maxOf(peak, peakDisplays(10))
            // one 12.0 lump at tick 20
            assertThat(lost(target)).isBetween(11.9, 30.0)
            assertThat(stacks(target, bleed)).isEqualTo(2)
            assertThat(spawnedBlocks("white_concrete")).isGreaterThan(0)
            assertThat(spawnedGlyphs("❖")).isGreaterThan(0)
            assertThat(peak).isLessThanOrEqualTo(liveDisplayBudget)
            assertAllDisplaysGone()
        }
    }

    @Test
    fun `katana S2 can be dodged by leaving the lane during the draw`() {
        scenario("persona-katana-s2-dodge") {
            val (caster, target) = duel("gossamer-katana", distance = 4.0)
            caster.rightClick()
            advance(10)
            target.position = Vec3(14.0, groundY, 4.0)
            advance(30)

            assertThat(lost(target)).isZero()
            assertThat(stacks(target, bleed)).isZero()
            assertAllDisplaysGone()
        }
    }

    @Test
    fun `katana S3 webs and bleeds everything near, then the scar spends the bleed`() {
        scenario("persona-katana-s3") {
            val (caster, target) = duel("gossamer-katana", distance = 2.5)
            sneakRightClick(caster)

            assertThat(caster.frozenTicks).isGreaterThan(0)
            var peak = peakDisplays(11)
            assertThat(target.glowing).isTrue()
            assertThat(lost(target)).isZero()

            // mid-spin: pulses landed, bleed stacked to its cap, threads and helix are up
            peak = maxOf(peak, peakDisplays(16))
            assertThat(lost(target)).isGreaterThan(0.0)
            assertThat(stacks(target, bleed)).isEqualTo(3)
            assertThat(spawnedBlocks("red_stained_glass")).isGreaterThan(0)
            assertThat(spawnedBlocks("light_blue_stained_glass")).isGreaterThanOrEqualTo(12)

            peak = maxOf(peak, peakDisplays(40))
            // 5.0 first pulse + (3.0 + 1.5 x 3) scar
            assertThat(lost(target)).isBetween(12.4, 40.0)
            assertThat(stacks(target, bleed)).isZero()
            assertThat(spawnedGlyphs("断")).isEqualTo(1)
            assertThat(peak).isLessThanOrEqualTo(liveDisplayBudget)
            assertAllDisplaysGone()
        }
    }

    @Test
    fun `katana DEF hops back and snags a close enemy with bleed and a short hold`() {
        scenario("persona-katana-def") {
            val (caster, target) = duel("gossamer-katana", distance = 2.0)
            sneakLeftClick(caster)

            assertThat(caster.velocity.z).isLessThan(0.0)
            assertThat(stacks(target, bleed)).isEqualTo(1)
            assertThat(target.frozenTicks).isGreaterThan(0)
            val peak = peakDisplays(24)
            assertThat(spawnedBlocks("red_stained_glass")).isGreaterThan(0)
            assertThat(peak).isLessThanOrEqualTo(liveDisplayBudget)
            assertAllDisplaysGone()
        }
    }

    @Test
    fun `katana DEF leaves a far enemy alone`() {
        scenario("persona-katana-def-far") {
            val (caster, target) = duel("gossamer-katana", distance = 8.0)
            sneakLeftClick(caster)
            advance(30)
            assertThat(stacks(target, bleed)).isZero()
            assertThat(target.frozenTicks).isZero()
            assertAllDisplaysGone()
        }
    }

    private companion object {
        const val DUMMY_HP = 200.0
    }
}
