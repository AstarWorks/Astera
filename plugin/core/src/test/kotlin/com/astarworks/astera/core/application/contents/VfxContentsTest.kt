package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.VfxConfigError
import com.astarworks.astera.core.application.config.VfxYamlConfig
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayItemMode
import com.astarworks.astera.core.domain.effect.vfx.VfxAlign
import com.astarworks.astera.core.domain.effect.vfx.VfxElementKind
import com.astarworks.astera.core.domain.effect.vfx.VfxId
import com.astarworks.astera.core.domain.effect.vfx.VfxOrient
import com.astarworks.astera.core.domain.effect.vfx.VfxOscillateAxis
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Curve
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** `content/vfx` YAML -> DTO -> domain round trip (ADR-0220). */
class VfxContentsTest {

    private val fullYaml = """
        id: crimson-cross
        duration_ticks: 24
        orient: to_target
        follow: true
        sample_ticks: 3
        elements:
          - id: slash
            kind: { type: block, block: red_stained_glass }
            brightness: 15
            glow_color: "#ff2040"
            billboard: center
            view_range: 1.5
            start_tick: 1
            end_tick: 14
            count: 5
            formation:
              curve: { type: arc, radius: 2.5, from_deg: -70, to_deg: 70 }
              tilt: { yaw: 0, pitch: 0, roll: 35 }
              offset: [0, 1.2, 0.8]
              align: tangent
              stagger_ticks: 1
              reverse: true
            jitter: { position: 0.15, rotation_deg: 8, scale: 0.1 }
            keyframes:
              - { t: 0, translate: [0, 0, 0], rotate: [0, 0, 45], scale: [0.04, 0.04, 0.2] }
              - { t: 3, scale: [0.08, 0.08, 6.0], ease: out_expo }
              - { t: 12, scale: [0.0, 0.0, 6.0], ease: in_quad, block: gray_stained_glass }
            oscillate: { axis: scale_xz, amplitude: 0.03, period_ticks: 2 }
          - id: glyph
            kind: { type: text, text: "<#ff2040><b>X</b>", background: "#80000000", shadow: true, see_through: true, line_width: 100 }
            keyframes:
              - { t: 0, text: "<red>A" }
          - id: weapon
            kind: { type: item, item: netherite_sword, display_mode: head }
            keyframes: [ { t: 0 } ]
          - id: sparks
            kind: { type: particle, particle_id: hit-spark, every_ticks: 2 }
            keyframes: [ { t: 0 } ]
    """.trimIndent()

    @Test
    fun `a full definition decodes into the domain model`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("crimson-cross.yaml"), fullYaml)
        val contents = VfxContents()
        val report = contents.bootstrap(dir)
        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(0)

        val def = contents.find(VfxId("crimson-cross"))!!
        assertThat(def.durationTicks).isEqualTo(24)
        assertThat(def.orient).isEqualTo(VfxOrient.TO_TARGET)
        assertThat(def.follow).isTrue()
        assertThat(def.sampleTicks).isEqualTo(3)

        val slash = def.elements[0]
        assertThat(slash.kind).isEqualTo(VfxElementKind.Block("red_stained_glass"))
        assertThat(slash.brightness).isEqualTo(15)
        assertThat(slash.glowArgb).isEqualTo(0xFFFF2040.toInt())
        assertThat(slash.billboard).isEqualTo(DisplayBillboard.CENTER)
        assertThat(slash.viewRange).isEqualTo(1.5f)
        assertThat(slash.count).isEqualTo(5)
        assertThat(slash.endTick).isEqualTo(14)
        val formation = slash.formation!!
        assertThat(formation.curve).isEqualTo(Curve.Arc(2.5, -70.0, 70.0))
        assertThat(formation.tiltYawPitchRoll).isEqualTo(Vec3(0.0, 0.0, 35.0))
        assertThat(formation.offset).isEqualTo(Vec3(0.0, 1.2, 0.8))
        assertThat(formation.align).isEqualTo(VfxAlign.TANGENT)
        assertThat(formation.staggerTicks).isEqualTo(1)
        assertThat(formation.reverse).isTrue()
        assertThat(slash.jitter!!.rotationDeg).isEqualTo(8.0)
        assertThat(slash.keyframes[0].rotateEuler).isEqualTo(Vec3(0.0, 0.0, 45.0))
        assertThat(slash.keyframes[1].ease).isEqualTo(Easing.EASE_OUT_EXPO)
        assertThat(slash.keyframes[1].translate).isNull()
        assertThat(slash.keyframes[2].block).isEqualTo("gray_stained_glass")
        assertThat(slash.oscillate!!.axis).isEqualTo(VfxOscillateAxis.SCALE_XZ)

        val text = def.elements[1].kind as VfxElementKind.Text
        assertThat(text.backgroundArgb).isEqualTo(0x80000000.toInt())
        assertThat(text.shadow).isTrue()
        assertThat(text.seeThrough).isTrue()
        assertThat(text.lineWidth).isEqualTo(100)

        val item = def.elements[2].kind as VfxElementKind.Item
        assertThat(item.displayMode).isEqualTo(DisplayItemMode.HEAD)
        assertThat(item.itemId.value).isEqualTo("netherite-sword")

        val particle = def.elements[3].kind as VfxElementKind.Particle
        assertThat(particle.particleId.value).isEqualTo("hit-spark")
        assertThat(particle.everyTicks).isEqualTo(2)
    }

    @Test
    fun `defaults apply to a minimal definition`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("min.yaml"),
            """
            id: min
            duration_ticks: 5
            elements:
              - id: e
                kind: { type: block, block: stone }
                keyframes: [ { t: 0 } ]
            """.trimIndent(),
        )
        val contents = VfxContents()
        contents.bootstrap(dir)
        val def = contents.find(VfxId("min"))!!
        assertThat(def.orient).isEqualTo(VfxOrient.LOOK)
        assertThat(def.follow).isFalse()
        assertThat(def.sampleTicks).isEqualTo(2)
        assertThat(def.elements.single().count).isEqualTo(1)
        assertThat(def.elements.single().formation).isNull()
    }

    @Test
    fun `every curve type decodes`(@TempDir dir: Path) {
        val curves = listOf(
            "{ type: line, from: [0, 0, 0], to: [0, 0, 5] }" to Curve.Line::class,
            "{ type: arc, radius: 1, from_deg: 0, to_deg: 90 }" to Curve.Arc::class,
            "{ type: circle, radius: 1 }" to Curve.Arc::class,
            "{ type: helix, radius: 1, turns: 2, height: 3 }" to Curve.Helix::class,
            "{ type: spiral, start_radius: 1, end_radius: 3, turns: 2 }" to Curve.Spiral::class,
            "{ type: bezier, points: [[0, 0, 0], [1, 2, 0], [2, 0, 0]] }" to Curve.Bezier::class,
            "{ type: polygon, radius: 2, sides: 5, skip: 2 }" to Curve.Polygon::class,
            "{ type: sphere, radius: 2 }" to Curve.FibonacciSphere::class,
            "{ type: lightning, from: [0, 5, 0], to: [0, 0, 0], segments: 6, amplitude: 0.4 }" to Curve.Lightning::class,
        )
        curves.forEachIndexed { i, (curve, type) ->
            val yaml = """
                id: curve-$i
                duration_ticks: 5
                elements:
                  - id: e
                    kind: { type: block, block: stone }
                    count: 3
                    formation: { curve: $curve }
                    keyframes: [ { t: 0 } ]
            """.trimIndent()
            val result = decode(yaml)
            val def = (result as Result.Success).value
            assertThat(def.elements.single().formation!!.curve).isInstanceOf(type.java)
        }
        // circle is closed so slots do not duplicate the start
        assertThat(Curve.Arc(1.0, 0.0, 360.0).closed).isTrue()
    }

    @Test
    fun `validation problems come back as errors, not exceptions`() {
        val bad = mapOf(
            "unknown ease" to fullYaml.replace("out_expo", "bouncy"),
            "unknown orient" to fullYaml.replace("to_target", "sideways"),
            "bad vector" to fullYaml.replace("[0, 1.2, 0.8]", "[0, 1.2]"),
            "bad colour" to fullYaml.replace("glow_color: \"#ff2040\"", "glow_color: \"red\""),
            "bad id" to fullYaml.replace("id: crimson-cross", "id: Crimson_Cross"),
            "unsorted keyframes" to fullYaml.replace("{ t: 3, scale", "{ t: 0, scale"),
            "block swap on a text element" to fullYaml.replace("text: \"<red>A\"", "block: stone"),
        )
        bad.forEach { (what, yaml) ->
            val result = decode(yaml)
            assertThat(result).describedAs(what).isInstanceOf(Result.Failure::class.java)
            assertThat((result as Result.Failure).error).isInstanceOf(VfxConfigError.Invalid::class.java)
            assertThat(result.error.message).isNotBlank()
        }
    }

    @Test
    fun `a broken file is counted as failed while the good one still loads`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("a.yaml"), fullYaml)
        Files.writeString(dir.resolve("b.yaml"), fullYaml.replace("id: crimson-cross", "id: b").replace("out_expo", "nope"))
        val contents = VfxContents()
        val report = contents.bootstrap(dir)
        assertThat(report.loaded).isEqualTo(1)
        assertThat(report.failed).isEqualTo(1)
        assertThat(contents.find(VfxId("b"))).isNull()
    }

    private fun decode(yaml: String): Result<com.astarworks.astera.core.domain.effect.vfx.VfxDefinition, VfxConfigError> {
        val config = com.astarworks.astera.core.application.config.parser.YamlContentParser().parse(yaml, VfxYamlConfig.serializer())
        return config.toDefinition()
    }
}
