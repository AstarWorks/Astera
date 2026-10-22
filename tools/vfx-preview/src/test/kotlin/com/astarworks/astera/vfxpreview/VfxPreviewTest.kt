package com.astarworks.astera.vfxpreview

import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.effect.vfx.VfxElementKind
import com.astarworks.astera.core.domain.effect.vfx.VfxElementPlan
import com.astarworks.astera.core.domain.effect.vfx.VfxParticleEmission
import com.astarworks.astera.core.domain.effect.vfx.VfxPlan
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.imageio.ImageIO

class VfxPreviewTest {

    private val opts = PreviewOptions(width = 480, height = 360)
    private val def = VfxPreview.load(File(System.getProperty("astera.contentDir"), "vfx/demo-cross.yaml"))

    @Test
    fun `replay reports the live display count per tick`() {
        val replay = PlanReplay(VfxPreview.plan(def, opts))
        // slash-a(0..14) bloom(1..12) slash-b(3..17) glyph(3..14) shards(3..6 staggered, 12 ticks each)
        assertThat(replay.liveCount(0)).isEqualTo(1)
        assertThat(replay.liveCount(2)).isEqualTo(2)
        assertThat(replay.liveCount(8)).isEqualTo(8)
        assertThat(replay.liveCount(23)).isZero()
        for (t in 0 until replay.plan.durationTicks) {
            val expected = replay.plan.elements.count { t >= it.spawnTick && t < it.despawnTick }
            assertThat(replay.liveCount(t)).isEqualTo(expected)
        }
    }

    @Test
    fun `transforms interpolate linearly between updates`() {
        val replay = PlanReplay(VfxPreview.plan(def, opts))
        val el = replay.plan.elements.first { it.elementId == "slash-a" }
        val first = el.updates.first()
        val before = replay.live(first.tick).first { it.elementId == "slash-a" }.transform
        val after = replay.live(first.tick + first.interpolationTicks).first { it.elementId == "slash-a" }.transform
        assertThat(before.scale.y).isEqualTo(el.initial.scale.y)
        assertThat(after.scale.y).isEqualTo(first.transform.scale.y)
        if (first.interpolationTicks > 1) {
            val mid = replay.live(first.tick + 1).first { it.elementId == "slash-a" }.transform
            assertThat(mid.scale.y).isStrictlyBetween(el.initial.scale.y, first.transform.scale.y)
        }
    }

    @Test
    fun `rendering demo-cross produces a non-empty frame per tick and a sheet`() {
        val effect = VfxPreview.render(def, opts)
        assertThat(effect.frames).hasSize(def.durationTicks)
        val empty = effect.frames.last() // everything has despawned
        assertThat(differingPixels(effect.frames[8], empty)).isGreaterThan(500)
        val sheet = VfxPreview.sheet(effect, opts)
        assertThat(sheet.width).isLessThanOrEqualTo(1800)
        assertThat(sheet.height).isGreaterThan(0)
    }

    @Test
    fun `sprite glyph text is recognised and its frame is tinted from the strip`(@TempDir dir: File) {
        val styled = MiniText.style("<#ff0000><font:astera:vfx/spark>\uE001</font>")
        assertThat(styled.sprite).isEqualTo(SpriteRef("spark", 1))
        assertThat(styled.rgb).isEqualTo(0xFF0000)
        assertThat(MiniText.style("<gold>plain").sprite).isNull()

        // 2 frames of 4px: frame 0 white, frame 1 solid green.
        val strip = BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 4) for (x in 0 until 8) strip.setRGB(x, y, if (x < 4) 0xFFFFFFFF.toInt() else 0xFF00FF00.toInt())
        File(dir, "textures/generated/sprites").mkdirs()
        ImageIO.write(strip, "png", File(dir, "textures/generated/sprites/spark.png"))
        val strips = SpriteStrips(dir)

        assertThat(strips.frame(SpriteRef("spark", 0), 0xFFFFFF)!!.getRGB(1, 1)).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(strips.frame(SpriteRef("spark", 0), 0xFF0000)!!.getRGB(1, 1)).isEqualTo(0xFFFF0000.toInt())
        assertThat(strips.frame(SpriteRef("spark", 1), 0xFFFFFF)!!.getRGB(1, 1)).isEqualTo(0xFF00FF00.toInt())
        assertThat(strips.frame(SpriteRef("spark", 2), 0xFFFFFF)).isNull()
        assertThat(strips.frame(SpriteRef("missing", 0), 0xFFFFFF)).isNull()
    }

    @Test
    fun `particle replay expands along_path streaks and carries direction`() {
        val emission = VfxParticleEmission(
            tick = 2,
            offset = Vec3(0.0, 0.0, 2.0),
            pathOffsets = listOf(Vec3(0.0, 0.0, 1.0), Vec3(0.0, 0.0, 2.0)),
            tangent = Vec3(0.0, 0.0, 1.0),
        )
        val element = VfxElementPlan(
            elementId = "streak",
            slot = 0,
            kind = VfxElementKind.Particle(ParticleId("streak"), alongPath = true),
            brightness = null,
            glowArgb = null,
            billboard = DisplayBillboard.FIXED,
            viewRange = null,
            spawnTick = 0,
            despawnTick = 5,
            initial = DisplayTransform.IDENTITY,
            updates = emptyList(),
            particles = listOf(emission),
        )
        val replay = PlanReplay(VfxPlan(Vec3.ZERO, 5, false, listOf(element)))
        val live = replay.particles(3)
        assertThat(live).hasSize(2)
        assertThat(live.map { it.position.z }).containsExactly(1.0, 2.0)
        assertThat(live.first().direction).isEqualTo(Vec3(0.0, 0.0, 1.0))
        assertThat(live.first().age).isEqualTo(1)
    }

    private fun differingPixels(a: BufferedImage, b: BufferedImage): Int {
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) n++
        return n
    }
}
