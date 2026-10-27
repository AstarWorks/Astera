package com.astarworks.astera.gamesim

import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.VfxIntensity
import com.astarworks.astera.core.domain.effect.vfx.VfxLane
import com.astarworks.astera.core.domain.effect.vfx.VfxQualityProfile
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** vfx-primitives T3: `viewers` audience per spawned display and the per-viewer `vfx_intensity` lane filter. */
class VfxAudienceIntensityScenarioTest {

    private val coreBlock = "white_stained_glass"
    private val accentBlock = "yellow_stained_glass"
    private val ambientBlock = "gray_stained_glass"

    private fun SimDisplayGateway.viewersOf(block: String) =
        spawned.filter { (it.spec.kind as? DisplayKind.Block)?.blockKey == block }.map { viewers.getValue(it.handle) }.single()

    @Test
    fun `each audience value shows the display to exactly its viewer set`() {
        val expected = mapOf(
            "all" to setOf("caster", "bystander", "victim"),
            "caster" to setOf("caster"),
            "others" to setOf("bystander", "victim"),
            "target" to setOf("victim"),
        )
        for ((audience, names) in expected) {
            scenario("vfx-audience-$audience") {
                val caster = player("caster", team = "red")
                val bystander = player("bystander", at = Vec3(8.0, groundY, -8.0), team = "blue")
                val victim = player("victim", at = Vec3(0.0, groundY, 6.0), team = "blue")
                caster.cast("vfx-demo-lanes-$audience")
                val ids = listOf(caster, bystander, victim).filter { it.name in names }.map { it.id }.toSet()
                assertThat(sim.displays.viewersOf(coreBlock)).describedAs(audience).isEqualTo(ids)
            }
        }
    }

    @Test
    fun `each intensity profile shows the right lanes to each viewer and the server floor downgrades everybody`() {
        scenario("vfx-intensity-profiles") {
            val caster = player("caster", team = "red")
            val full = player("full", at = Vec3(3.0, groundY, 0.0), team = "red")
            val reduced = player("reduced", at = Vec3(-3.0, groundY, 0.0), team = "red")
            val minimal = player("minimal", at = Vec3(0.0, groundY, -3.0), team = "red")
            sim.displays.intensity[reduced.id] = VfxIntensity.REDUCED
            sim.displays.intensity[minimal.id] = VfxIntensity.MINIMAL

            caster.cast("vfx-demo-lanes-all")

            val core = sim.displays.viewersOf(coreBlock)
            val accent = sim.displays.viewersOf(accentBlock)
            val ambient = sim.displays.viewersOf(ambientBlock)
            assertThat(core).containsExactlyInAnyOrder(caster.id, full.id, reduced.id, minimal.id)
            assertThat(accent).containsExactlyInAnyOrder(caster.id, full.id, reduced.id)
            assertThat(ambient).containsExactlyInAnyOrder(caster.id, full.id)

            // element counts per viewer = the profile's lane count (3 / 2 / 1)
            val counts = listOf(full, reduced, minimal).map { v -> sim.displays.viewers.values.count { v.id in it } }
            assertThat(counts).containsExactly(3, 2, 1)
            assertThat(listOf(VfxIntensity.FULL, VfxIntensity.REDUCED, VfxIntensity.MINIMAL).map {
                VfxQualityProfile.count(it, listOf(VfxLane.CORE, VfxLane.ACCENT, VfxLane.AMBIENT))
            }).containsExactly(3, 2, 1)
        }
    }

    @Test
    fun `a pressured display budget forces the lean profile for everybody`() {
        scenario("vfx-intensity-floor") {
            val caster = player("caster", team = "red")
            val watcher = player("watcher", at = Vec3(3.0, groundY, 0.0), team = "red")
            sim.displays.serverFloor = VfxQualityProfile.serverFloor(liveDisplays = 95, cap = 100)

            caster.cast("vfx-demo-lanes-all")

            assertThat(sim.displays.serverFloor).isEqualTo(VfxIntensity.MINIMAL)
            assertThat(sim.displays.viewersOf(coreBlock)).containsExactlyInAnyOrder(caster.id, watcher.id)
            assertThat(sim.displays.spawned.map { it.handle }.flatMap { sim.displays.viewers.getValue(it) }.toSet()).isNotEmpty
            assertThat(sim.displays.viewersOf(accentBlock)).isEmpty()
            assertThat(sim.displays.viewersOf(ambientBlock)).isEmpty()
        }
    }
}
