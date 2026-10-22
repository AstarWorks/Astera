package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.domain.effect.ParticleColor
import com.astarworks.astera.core.domain.effect.ParticleData
import com.astarworks.astera.core.domain.effect.ParticleDefinition
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.effect.ParticleShape
import com.astarworks.astera.core.domain.effect.ParticleTarget
import com.astarworks.astera.core.domain.effect.ParticleVelocity
import com.astarworks.astera.core.domain.effect.ParticleViewers
import com.astarworks.astera.core.domain.effect.VelocityDirection
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** YAML -> domain mapping for the rich particle fields (data families, velocity, force, viewers, shapes). */
class ParticleRichYamlTest {

    private fun load(dir: Path, particle: String, extra: String, shape: String = "kind: point"): ParticleDefinition {
        Files.writeString(
            dir.resolve("p.yaml"),
            """
            id: p
            shape:
              $shape
            color: {r: 10, g: 20, b: 30}
            count: 3
            lifetime_ticks: 10
            vanilla_particle: $particle
            """.trimIndent() + "\n" + extra.trimIndent() + "\n",
        )
        val contents = ParticleContents()
        val report = contents.bootstrap(dir)
        assertThat(report.failed).describedAs("load of $particle").isEqualTo(0)
        return requireNotNull(contents.find(ParticleId("p")))
    }

    private val c = ParticleColor(1, 2, 3)

    @Test
    fun `defaults - force true, viewers all, no data, no velocity`(@TempDir dir: Path) {
        val d = load(dir, "flame", "")
        assertThat(d.force).isTrue()
        assertThat(d.viewers).isEqualTo(ParticleViewers.ALL)
        assertThat(d.data).isNull()
        assertThat(d.velocity).isNull()
    }

    @Test
    fun `dust and dust_color_transition`(@TempDir dir: Path) {
        assertThat(load(dir, "dust", "data: {kind: dust, color: {r: 1, g: 2, b: 3}, scale: 2.0}").data)
            .isEqualTo(ParticleData.Dust(c, 2.0f))
        assertThat(
            load(dir, "dust_color_transition", "data: {kind: dust_color_transition, from: {r: 1, g: 2, b: 3}, to: {r: 4, g: 5, b: 6}}").data,
        ).isEqualTo(ParticleData.DustTransition(c, ParticleColor(4, 5, 6), 1.0f))
    }

    @Test
    fun `tint and spell`(@TempDir dir: Path) {
        assertThat(load(dir, "flash", "data: {kind: tint, color: {r: 1, g: 2, b: 3}}").data).isEqualTo(ParticleData.Tint(c))
        assertThat(load(dir, "effect", "data: {kind: spell, color: {r: 1, g: 2, b: 3}, power: 0.5}").data)
            .isEqualTo(ParticleData.Spell(c, 0.5f))
    }

    @Test
    fun `block-data particles and item`(@TempDir dir: Path) {
        for (key in listOf("block", "block_crumble", "block_marker", "falling_dust", "dust_pillar")) {
            assertThat(load(dir, key, "data: {kind: block, block: stone}").data).isEqualTo(ParticleData.Block("stone"))
        }
        assertThat(load(dir, "item", "data: {kind: item, item: iron_sword}").data).isEqualTo(ParticleData.Item("iron_sword"))
    }

    @Test
    fun `trail with an offset or toward target`(@TempDir dir: Path) {
        assertThat(
            load(dir, "trail", "data: {kind: trail, target_offset: [0, 2, 0], color: {r: 1, g: 2, b: 3}, duration_ticks: 12}").data,
        ).isEqualTo(ParticleData.Trail(ParticleTarget.Offset(Vec3(0.0, 2.0, 0.0)), c, 12))
        assertThat(load(dir, "trail", "data: {kind: trail, toward: true, color: {r: 1, g: 2, b: 3}}").data)
            .isEqualTo(ParticleData.Trail(ParticleTarget.Toward, c, 20))
    }

    @Test
    fun `sculk_charge shriek vibration power`(@TempDir dir: Path) {
        assertThat(load(dir, "sculk_charge", "data: {kind: sculk_charge, roll: 1.5}").data).isEqualTo(ParticleData.SculkCharge(1.5f))
        assertThat(load(dir, "shriek", "data: {kind: shriek, delay: 5}").data).isEqualTo(ParticleData.Shriek(5))
        assertThat(load(dir, "vibration", "data: {kind: vibration, toward: true, arrival_ticks: 8}").data)
            .isEqualTo(ParticleData.Vibration(ParticleTarget.Toward, 8))
        assertThat(load(dir, "dragon_breath", "data: {kind: power, power: 0.3}").data).isEqualTo(ParticleData.Power(0.3f))
    }

    @Test
    fun `velocity directions force and viewers`(@TempDir dir: Path) {
        val d = load(
            dir,
            "end_rod",
            """
            velocity: {direction: along_tangent, speed: 0.3, spread_degrees: 6}
            force: false
            viewers: others
            """,
        )
        assertThat(d.velocity).isEqualTo(ParticleVelocity(VelocityDirection.AlongTangent, 0.3, 6.0))
        assertThat(d.force).isFalse()
        assertThat(d.viewers).isEqualTo(ParticleViewers.OTHERS)

        assertThat(load(dir, "end_rod", "velocity: {direction: custom, custom: [1, 0, 0], speed: 0.1}").velocity)
            .isEqualTo(ParticleVelocity(VelocityDirection.Custom(Vec3(1.0, 0.0, 0.0)), 0.1))
        for ((name, expected) in listOf(
            "from_caster_look" to VelocityDirection.FromCasterLook,
            "outward" to VelocityDirection.Outward,
            "up" to VelocityDirection.Up,
        )) {
            assertThat(load(dir, "end_rod", "velocity: {direction: $name}").velocity?.direction).isEqualTo(expected)
        }
    }

    @Test
    fun `new shapes`(@TempDir dir: Path) {
        assertThat(load(dir, "flame", "", "{kind: ring, radius_blocks: 2.0, points: 16}").shape).isEqualTo(ParticleShape.Ring(2.0, 16))
        assertThat(load(dir, "flame", "", "{kind: arc, radius_blocks: 2.0, from_deg: -45, to_deg: 45, points: 9}").shape)
            .isEqualTo(ParticleShape.Arc(2.0, -45.0, 45.0, 9))
        assertThat(load(dir, "flame", "", "{kind: helix, radius_blocks: 1.0, height_blocks: 3.0, turns: 2.0, points: 30}").shape)
            .isEqualTo(ParticleShape.Helix(1.0, 3.0, 2.0, 30))
        assertThat(load(dir, "flame", "", "{kind: line, length_blocks: 4.0, points: 8}").shape).isEqualTo(ParticleShape.Line(4.0, 8))
        assertThat(load(dir, "flame", "", "{kind: sphere, radius_blocks: 1.5, points: 20}").shape).isEqualTo(ParticleShape.Sphere(1.5, 20))
    }

    @Test
    fun `bad rich fields fail the load`(@TempDir dir: Path) {
        fun failed(extra: String): Boolean {
            Files.writeString(
                dir.resolve("p.yaml"),
                "id: p\nshape: {kind: point}\ncolor: {r: 1, g: 2, b: 3}\ncount: 1\nlifetime_ticks: 1\nvanilla_particle: trail\n$extra\n",
            )
            return ParticleContents().bootstrap(dir).failed == 1
        }
        assertThat(failed("data: {kind: trail, color: {r: 1, g: 2, b: 3}}")).isTrue() // no target
        assertThat(failed("data: {kind: nope}")).isTrue()
        assertThat(failed("velocity: {direction: sideways}")).isTrue()
        assertThat(failed("viewers: nobody")).isTrue()
        assertThat(failed("data: {kind: block}")).isTrue()
    }
}
