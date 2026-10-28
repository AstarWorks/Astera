package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.runtime.port.DisplayView
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose
import com.astarworks.astera.core.application.skillengine.runtime.presentation.CameraShakeRequest
import com.astarworks.astera.core.application.skillengine.runtime.presentation.ISkillScreenGateway
import com.astarworks.astera.core.domain.effect.Audience
import com.astarworks.astera.core.domain.effect.AudienceResolver
import com.astarworks.astera.core.domain.effect.VfxIntensity
import com.astarworks.astera.core.domain.effect.VfxLane
import com.astarworks.astera.core.domain.effect.VfxQualityProfile
import com.astarworks.astera.core.domain.effect.camera.ShakeDirection
import com.astarworks.astera.core.domain.effect.camera.ShakeShape
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP6-C (v1 VfxPlayer.kt:88 audience + lane, WorldMechanics.kt:614 camera_shake): real converted shipped skills
 * (`vfx-demo-lanes-*`, `vfx-demo-veil-shake`, a scenario shake) through the real decoder + executors; the gateway
 * resolves viewers exactly as the Paper gateway does (`AudienceResolver.displayPool/select`, then
 * `VfxQualityProfile.filterViewers`). Who receives what, per audience and per vfx intensity.
 */
class VfxAudienceScenarioTest {

    private val caster = PlayerId(SIM_CASTER)
    private val bystander = PlayerId(simVictim(7))
    private val victim = PlayerId(simVictim(1))
    private val everyone = listOf(caster, bystander, victim)

    private class Seen(val kind: String, val view: DisplayView, val viewers: Set<PlayerId>)

    private class AudienceVisuals(
        private val inner: ISkillVisualGateway,
        private val pool: List<PlayerId>,
        private val casterId: PlayerId,
        private val intensity: (PlayerId) -> VfxIntensity,
    ) : ISkillVisualGateway by inner {
        val seen = ArrayList<Seen>()

        private fun note(kind: String, view: DisplayView) {
            val target = view.target?.let(::PlayerId)
            val chosen = AudienceResolver.select(AudienceResolver.displayPool(pool, casterId, target), view.audience, casterId, target)
            seen += Seen(kind, view, VfxQualityProfile.filterViewers(chosen, view.lane, intensity))
        }

        override fun spawnBlock(castOwner: UUID, block: String, pose: VisualPose, brightness: Int?, rigShading: Boolean, view: DisplayView): VisualHandle {
            note(block, view)
            return inner.spawnBlock(castOwner, block, pose, brightness, rigShading, view)
        }
    }

    private class Shakes : ISkillScreenGateway by noopScreen() {
        val got = ArrayList<Pair<UUID, CameraShakeRequest>>()
        override fun cameraShake(viewer: UUID, shake: CameraShakeRequest) { got += viewer to shake }
    }

    private fun lanes(audience: String, intensity: (PlayerId) -> VfxIntensity = { VfxIntensity.FULL }): Map<PlayerId, Set<VfxLane>> {
        lateinit var sim: CastSim
        val rec = RecordingVisuals({ sim.now }, { sim.casterFeet })
        val visuals = AudienceVisuals(rec, everyone, caster, intensity)
        sim = CastSim(
            victims = mapOf(victim.uuid to Vec3(0.0, 0.0, 6.0), bystander.uuid to Vec3(8.0, 0.0, -8.0)),
            visualGateway = visuals,
        )
        sim.cast("vfx-demo-lanes-$audience", 60)
        assertThat(visuals.seen).describedAs("three lane displays spawned for $audience").hasSize(3)
        assertThat(visuals.seen.map { it.view.audience }.toSet()).hasSize(1)
        return everyone.associateWith { p -> visuals.seen.filter { p in it.viewers }.map { it.view.lane }.toSet() }
    }

    private val all = setOf(VfxLane.CORE, VfxLane.ACCENT, VfxLane.AMBIENT)

    @Test
    fun `audience all shows every lane to every player`() {
        assertThat(lanes("all")).containsEntry(caster, all).containsEntry(bystander, all).containsEntry(victim, all)
    }

    @Test
    fun `audience caster shows the caster only`() {
        assertThat(lanes("caster")).containsEntry(caster, all).containsEntry(bystander, emptySet()).containsEntry(victim, emptySet())
    }

    @Test
    fun `audience others hides it from the caster`() {
        assertThat(lanes("others")).containsEntry(caster, emptySet()).containsEntry(bystander, all).containsEntry(victim, all)
    }

    @Test
    fun `audience target shows the bound victim only`() {
        assertThat(lanes("target")).containsEntry(caster, emptySet()).containsEntry(bystander, emptySet()).containsEntry(victim, all)
    }

    @Test
    fun `vfx intensity drops lanes per viewer, full 3 reduced 2 minimal 1`() {
        val byViewer = mapOf(caster to VfxIntensity.FULL, bystander to VfxIntensity.REDUCED, victim to VfxIntensity.MINIMAL)
        val got = lanes("all") { byViewer.getValue(it) }
        assertThat(got.getValue(caster)).isEqualTo(all)
        assertThat(got.getValue(bystander)).isEqualTo(setOf(VfxLane.CORE, VfxLane.ACCENT))
        assertThat(got.getValue(victim)).isEqualTo(setOf(VfxLane.CORE))
    }

    @Test
    fun `camera shake reaches only the resolved viewers with the declared shape direction and frequency`() {
        fun shake(viewers: String): Shakes {
            val rows = "{id: s, at: 0, do: [{id: sh, op: camera_shake, target: {enemies: {around: caster, radius: 20}}, intensity: 0.8, ticks: 12, shape: impulse, direction: from_hit, frequency: 6.0, viewers: $viewers}]}"
            val shakes = Shakes()
            val sim = CastSim(
                victims = mapOf(victim.uuid to Vec3(0.0, 0.0, 6.0), bystander.uuid to Vec3(8.0, 0.0, -8.0)),
                extraSkills = mapOf(shakeSkill("t-shake", rows)),
                screenGateway = shakes,
            )
            sim.cast("t-shake", 20)
            return shakes
        }
        val all = shake("all")
        assertThat(all.got.map { it.first }.toSet()).containsExactlyInAnyOrder(victim.uuid, bystander.uuid)
        val r = all.got.first().second
        assertThat(r.shape).isEqualTo(ShakeShape.IMPULSE)
        assertThat(r.direction).isEqualTo(ShakeDirection.FROM_HIT)
        assertThat(r.frequencyHz).isEqualTo(6.0)
        assertThat(r.intensity).isEqualTo(0.8)
        assertThat(r.ticks).isEqualTo(12)
        assertThat(r.hitFrom).describedAs("FROM_HIT shakes away from the caster").isEqualTo(SIM_CASTER)
        assertThat(all.got.map { it.second.seed }.toSet()).describedAs("per-viewer seeds differ").hasSize(2)

        assertThat(shake("others").got.map { it.first }.toSet()).containsExactlyInAnyOrder(victim.uuid, bystander.uuid)
        assertThat(shake("caster").got).describedAs("the enemy-only target set never contains the caster").isEmpty()
    }

    @Test
    fun `the shipped veil shake demo shakes the caster only`() {
        val shakes = Shakes()
        val sim = CastSim(victims = emptyMap(), screenGateway = shakes)
        sim.cast("vfx-demo-veil-shake", 30)
        assertThat(shakes.got.map { it.first }).containsExactly(SIM_CASTER)
        val r = shakes.got.single().second
        assertThat(r.shape).isEqualTo(ShakeShape.DECAY)
        assertThat(r.direction).isEqualTo(ShakeDirection.NONE)
        assertThat(r.hitFrom).isNull()
    }

    private fun shakeSkill(id: String, rows: String): Pair<String, String> =
        id to """
            apiVersion: "astera/v2"
            kind: Skill
            id: $id
            item: pistol
            meta: {name: {ja: "t", en: "t"}, description: {ja: "t", en: "t"}, cooldown_ticks: 1, resource_cost: 0, tags: []}
            frames: []
            gameplay: {timeline: []}
            presentation: ${"{timeline: [$rows]}"}
        """.trimIndent()
}

private fun noopScreen(): ISkillScreenGateway = noop()
