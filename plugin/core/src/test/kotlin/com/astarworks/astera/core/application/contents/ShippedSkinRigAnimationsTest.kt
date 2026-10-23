package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.skinrig.RigFrame
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigPlayback
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigTransforms
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * The shipped `content/animations/` load through the real [SkinRigContents] loader and play through the
 * real [SkinRigPlayback] / [SkinRigTransforms] (ADR-0250): every file name is its id, every timeline ends
 * at the rest pose (no pop when the real player comes back), and every transform is finite.
 */
class ShippedSkinRigAnimationsTest {

    private val dir: Path = locateContentRoot().resolve("animations")

    private fun load(): SkinRigContents {
        val contents = SkinRigContents()
        val report = contents.bootstrap(dir)
        assertThat(report.failures)
            .withFailMessage { report.failures.joinToString("; ") { "${it.file.fileName} -> ${it.message}" } }
            .isEmpty()
        return contents
    }

    @Test
    fun `file names are animation ids and the two PoC animations ship`() {
        val contents = load()
        val stems = Files.list(dir).use { s -> s.filter { it.extension == "yaml" }.map { it.nameWithoutExtension }.toList() }
        assertThat(stems).isNotEmpty()
        for (stem in stems) assertThat(contents.find(CustomAnimationId(stem))).`as`("animation $stem").isNotNull()
        assertThat(stems).contains("sword-swing", "wave")
        assertThat(contents.find(CustomAnimationId("sword-swing"))!!.loop).isFalse()
        assertThat(contents.find(CustomAnimationId("wave"))!!.loop).isFalse()
    }

    @Test
    fun `every animation starts and ends at the rest pose with finite transforms`() {
        val contents = load()
        for (animation in contents.all()) {
            val playback = SkinRigPlayback(animation)
            var frames = 0
            var last = playback.frame()
            assertThat(last).`as`("${animation.id} first frame").isEqualTo(RigFrame.REST)
            while (!playback.isFinished) {
                last = playback.frame()
                for (slim in listOf(false, true)) {
                    val t = SkinRigTransforms.compute(last, slim)
                    val numbers = t.bones.values.flatMap { listOf(it.translation.x, it.translation.y, it.translation.z, it.scale.x, it.scale.y, it.scale.z, it.rotation.w) }
                    assertThat(numbers).`as`("${animation.id} tick $frames").allMatch { it.isFinite() }
                }
                playback.advance()
                frames++
                assertThat(frames).`as`("${animation.id} terminates").isLessThan(animation.lengthTicks + animation.blendOutTicks + 2)
            }
            // The tick after the last shown frame is weight 0: exactly the rest pose.
            assertThat(playback.frame()).`as`("${animation.id} final frame").isEqualTo(RigFrame.REST)
            assertThat(frames).`as`("${animation.id} duration").isEqualTo(animation.lengthTicks + animation.blendOutTicks)
        }
    }

    @Test
    fun `the rest pose reproduces the vanilla player boxes`() {
        val rest = SkinRigTransforms.compute(RigFrame.REST, slim = false).bones
        val head = rest.getValue(SkinRigBone.HEAD)
        assertThat(head.translation.y).isCloseTo(28.0 / 16.0, Offset.offset(1e-9))
        assertThat(head.scale.x).isCloseTo(1.0, Offset.offset(1e-9))
        val arm = rest.getValue(SkinRigBone.RIGHT_ARM)
        assertThat(arm.translation.x).isCloseTo(-6.0 / 16.0, Offset.offset(1e-9))
        assertThat(arm.scale.x).isCloseTo(0.5, Offset.offset(1e-9))
        assertThat(arm.scale.y).isCloseTo(1.5, Offset.offset(1e-9))
        val slimArm = SkinRigTransforms.compute(RigFrame.REST, slim = true).bones.getValue(SkinRigBone.LEFT_ARM)
        assertThat(slimArm.scale.x).isCloseTo(3.0 / 8.0, Offset.offset(1e-9))
    }

    @Test
    fun `sword swing raises the right arm forward during the slash`() {
        val animation = load().find(CustomAnimationId("sword-swing"))!!
        val playback = SkinRigPlayback(animation)
        var minX = 0.0
        while (!playback.isFinished) {
            minX = minOf(minX, playback.frame().bones.getValue(SkinRigBone.RIGHT_ARM).rotation.x)
            playback.advance()
        }
        assertThat(minX).isLessThan(-100.0)
    }

    private fun locateContentRoot(): Path {
        var cur: Path? = Path.of("").toAbsolutePath()
        while (cur != null) {
            if (Files.isRegularFile(cur.resolve("settings.gradle.kts")) && Files.isDirectory(cur.resolve("content"))) {
                return cur.resolve("content")
            }
            cur = cur.parent
        }
        error("content/ not found above ${Path.of("").toAbsolutePath()}")
    }
}
