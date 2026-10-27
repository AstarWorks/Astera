package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.service.MechanicDispatcher
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.ISkinRigRegistry
import com.astarworks.astera.core.application.port.outbound.IVfxRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.vfx.VfxBodyContext
import com.astarworks.astera.core.domain.effect.vfx.VfxFreeze
import com.astarworks.astera.core.domain.effect.vfx.VfxId
import com.astarworks.astera.core.domain.effect.vfx.VfxPerformanceSampler
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.presentation.PresentationKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * ADR-0276: a skill performance (skin-rig body + ONE hand-parented weapon) shows exactly one weapon to every viewer on every
 * tick, through start, end, interruption, death, quit, world change and a viewer that starts tracking mid-skill; and the weapon
 * stays at the right hand while the body animates. Real wiring, real shipped content (`vfx-demo-performance`), the headless
 * presentation lease with its per-viewer client model.
 */
class PerformanceScenarioTest {

    private fun ScenarioScope.assertOneWeapon(probe: PerformanceProbe, label: String) {
        for (viewer in probe.viewers()) {
            assertThat(probe.visibleWeapons(viewer)).describedAs("$label: weapons seen by ${viewer.name} at tick ${sim.tick}").isEqualTo(1)
        }
    }

    private fun ScenarioScope.assertRestored(caster: SimEntity, probe: PerformanceProbe) {
        assertThat(sim.presentation.holder(caster.id)).isNull()
        assertThat(sim.displays.active).isEmpty()
        for (viewer in probe.viewers()) {
            val view = sim.presentation.view(viewer.id, caster.id)
            assertThat(view.handHidden).describedAs("${viewer.name} hand").isFalse()
            assertThat(view.bodyHidden).describedAs("${viewer.name} body").isFalse()
            assertThat(view.revealed).describedAs("${viewer.name} revealed").isEmpty()
        }
        assertThat(sim.presentation.pinnedPoses).isEmpty()
    }

    @Test
    fun `the performance shows one weapon to every viewer on every tick and gives everything back`() {
        scenario("performance-one-weapon") {
            val caster = player("caster", team = "red")
            player("bystander", at = Vec3(4.0, groundY, 0.0), team = "blue")
            player("far", at = Vec3(0.0, groundY, 9.0), team = "blue")
            val probe = PerformanceProbe(sim, caster)

            assertOneWeapon(probe, "before the cast")
            caster.cast("vfx-demo-performance")
            // six bones + one weapon, all riding the caster
            val kinds = sim.displays.spawned.map { it.spec.kind }
            assertThat(kinds.filterIsInstance<DisplayKind.SkinBone>()).hasSize(6)
            assertThat(kinds.count { it == DisplayKind.AnchorMainHand }).isEqualTo(1)
            assertThat(sim.displays.attached.values).allMatch { it == caster.id }
            assertThat(sim.presentation.holder(caster.id)).isEqualTo(PresentationKind.PERFORMANCE)

            repeat(30) {
                assertOneWeapon(probe, "tick $it")
                advance(1)
            }
            assertOneWeapon(probe, "after the end")
            assertRestored(caster, probe)
            // ADR-0282: the weapon_model keys swapped the model of the SAME display (the count stayed 1 on every tick above)
            val weapon = sim.displays.spawned.single { it.spec.kind == DisplayKind.AnchorMainHand }.handle
            assertThat(sim.displays.swaps.filter { it.handle == weapon && it.kind == "item_model" }.map { it.value })
                .containsExactly("minecraft:diamond_sword", "minecraft:golden_sword")
            // the cut at rig tick 6 was flushed as one bundle (hand and hilt jump in the same client step)
            assertThat(sim.presentation.log.filter { it.startsWith("flush") }).isNotEmpty()
        }
    }

    @Test
    fun `a rig keeps the lease until its last display ends even when an earlier element despawns`() {
        scenario("performance-lease-outlives-early-elements") {
            val caster = player("caster", team = "red")
            val probe = PerformanceProbe(sim, caster)
            caster.cast("couture-rapier-s2")
            // couture-s2-rig: the flying shears despawn at rig tick 58, the body runs to 63; the hand must never come back before
            repeat(60) {
                assertThat(sim.presentation.holder(caster.id)).describedAs("lease at tick $it").isEqualTo(PresentationKind.PERFORMANCE)
                assertOneWeapon(probe, "tick $it")
                advance(1)
            }
        }
    }

    @Test
    fun `an interrupting cast replaces the performance with no tick of zero or two weapons`() {
        scenario("performance-interrupted") {
            val caster = player("caster", team = "red")
            player("bystander", at = Vec3(4.0, groundY, 0.0), team = "blue")
            val probe = PerformanceProbe(sim, caster)

            caster.cast("vfx-demo-performance")
            repeat(9) { advance(1); assertOneWeapon(probe, "first cast, tick $it") }
            // same replace_group: the second performance claims its lease before the first one is released
            caster.cast("vfx-demo-performance")
            assertOneWeapon(probe, "replacement tick")
            repeat(30) { advance(1); assertOneWeapon(probe, "second cast, tick $it") }
            assertRestored(caster, probe)
        }
    }

    @Test
    fun `death ends the performance in the same tick`() {
        scenario("performance-death") {
            val caster = player("caster", team = "red")
            player("bystander", at = Vec3(4.0, groundY, 0.0), team = "blue")
            val probe = PerformanceProbe(sim, caster)
            caster.cast("vfx-demo-performance")
            repeat(7) { advance(1); assertOneWeapon(probe, "tick $it") }

            sim.eventBus.publish(PlayerKilled(caster.id, null, DamageSource.Environment.Fall(1.0), sim.tick))
            assertRestored(caster, probe)
            assertOneWeapon(probe, "after death")
        }
    }

    @Test
    fun `quit and world change end the performance in the same tick`() {
        scenario("performance-quit-world-change") {
            val caster = player("caster", team = "red")
            player("bystander", at = Vec3(4.0, groundY, 0.0), team = "blue")
            val probe = PerformanceProbe(sim, caster)

            caster.cast("vfx-demo-performance")
            repeat(5) { advance(1) }
            sim.eventBus.publish(PlayerQuitServer(caster.id))
            assertRestored(caster, probe)
            assertOneWeapon(probe, "after quit")

            advance(70) // cooldown
            caster.cast("vfx-demo-performance")
            repeat(5) { advance(1) }
            sim.services.getOrThrow<MechanicDispatcher>().endPerformances(caster.id) // what PlayerChangedWorldEvent triggers
            assertRestored(caster, probe)
            assertOneWeapon(probe, "after world change")
        }
    }

    @Test
    fun `a viewer that starts tracking mid-skill sees one weapon`() {
        scenario("performance-late-tracking") {
            val caster = player("caster", team = "red")
            val probe = PerformanceProbe(sim, caster)
            caster.cast("vfx-demo-performance")
            repeat(8) { advance(1) }

            val late = player("late", at = Vec3(3.0, groundY, 3.0), team = "blue")
            // PlayerTrackEntityEvent: the pairing data carries the real hand, the hook masks it in the same tick
            assertThat(probe.visibleWeapons(late)).isEqualTo(1)
            sim.presentation.viewerTracks(late.id, caster.id)
            assertOneWeapon(probe, "right after tracking")
            repeat(20) { advance(1); assertOneWeapon(probe, "tick $it") }
            assertRestored(caster, probe)
        }
    }

    @Test
    fun `the weapon stays in the right hand while the body animates`() {
        scenario("performance-hand-lock") {
            player("caster", team = "red")
            val def = checkNotNull(sim.services.getOrThrow<IVfxRegistry>().find(VfxId("demo-performance")))
            val animation = checkNotNull(sim.services.getOrThrow<ISkinRigRegistry>().find(def.body!!))
            val ticks = VfxPerformanceSampler.sample(def, VfxBodyContext(animation), listOf(VfxFreeze(9, 2)))

            fun dist(a: List<Double>, b: List<Double>) = sqrt((0..2).sumOf { (a[it] - b[it]) * (a[it] - b[it]) })
            val offsets = ticks.map { t ->
                val w = t.weapon!!
                val hand = t.bones.getValue("right_hand")
                dist(listOf(w.pos.x, w.pos.y, w.pos.z), listOf(hand.pos.x, hand.pos.y, hand.pos.z))
            }
            // tolerance of skill-lint (grip_tolerance 0.3) and, stronger, no drift: the grip sits at the same place of the arm
            assertThat(offsets).allMatch { it < 0.3 }
            assertThat(offsets.max() - offsets.min()).isLessThan(1e-3)
            // the body really moves (otherwise the lock proves nothing)
            val hands = ticks.map { it.bones.getValue("right_hand").pos.y }
            assertThat(hands.max() - hands.min()).isGreaterThan(0.5)
        }
    }
}
