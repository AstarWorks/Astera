package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.domain.effect.vfx.SpriteFrames
import com.astarworks.astera.core.domain.effect.vfx.VfxDefinition
import com.astarworks.astera.core.domain.effect.vfx.VfxElementKind
import com.astarworks.astera.core.domain.effect.vfx.VfxFreeze
import com.astarworks.astera.core.domain.effect.vfx.VfxId
import com.astarworks.astera.core.domain.effect.vfx.VfxTimeline
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** The shipped weapon-rig demo (`content/vfx/demo-greatsword-swing.yaml`) decodes and plans. */
class VfxRigContentTest {

    @Test
    fun `the demo greatsword swing loads and plans a fan within the slot cap`() {
        val contents = VfxContents()
        val report = contents.bootstrap(locateContentRoot().resolve("vfx"))
        assertThat(report.failed).isEqualTo(0)
        val def = contents.find(VfxId("demo-greatsword-swing"))!!
        assertThat(def.rigs).hasSize(1)

        val plan = VfxTimeline.plan(def, Frame.fromYawPitch(Vec3.ZERO, 0.0, 0.0), 1L, 1.0, SpriteFrames.NONE, listOf(VfxFreeze(7, 3)))
        assertThat(plan.displayCount).isBetween(20, VfxDefinition.MAX_SLOTS)
        assertThat(plan.elements.count { it.elementId.startsWith("wind.b") }).isGreaterThan(20)
        assertThat(plan.elements.filter { it.kind is VfxElementKind.Quad }).allMatch { it.updates.isNotEmpty() }
        plan.elements.forEach { assertThat(it.despawnTick).isLessThanOrEqualTo(def.durationTicks) }
    }

    private fun locateContentRoot(): Path {
        var cur: Path? = Path.of("").toAbsolutePath()
        while (cur != null) {
            if (Files.isRegularFile(cur.resolve("settings.gradle.kts")) && Files.isDirectory(cur.resolve("content"))) {
                return cur.resolve("content")
            }
            cur = cur.parent
        }
        error("repo root (settings.gradle.kts + content/) not found")
    }
}
