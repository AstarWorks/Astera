package com.astarworks.astera.vfxpreview

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File

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

    private fun differingPixels(a: BufferedImage, b: BufferedImage): Int {
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getRGB(x, y) != b.getRGB(x, y)) n++
        return n
    }
}
