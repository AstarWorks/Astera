package com.astarworks.astera.combat.application.preview

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

class PreviewGeometryTest {

    private val viewer = Vec3(10.0, 64.0, 10.0)

    // Viewer looking along +X.
    private val layout = PreviewGeometry.layout(viewer, Vec3(1.0, 0.3, 0.0))

    @Test
    fun `layout puts caster 3 ahead and target 4 beyond on the horizontal heading`() {
        assertThat(layout.caster).isEqualTo(Vec3(13.0, 64.0, 10.0))
        assertThat(layout.target).isEqualTo(Vec3(17.0, 64.0, 10.0))
        assertThat(layout.axis).isEqualTo(Vec3(1.0, 0.0, 0.0))
    }

    @Test
    fun `layout survives a vertical look direction`() {
        val l = PreviewGeometry.layout(viewer, Vec3(0.0, 1.0, 0.0))
        assertThat(l.axis).isEqualTo(Vec3(0.0, 0.0, 1.0))
    }

    @Test
    fun `side camera is perpendicular to the caster-target line at the midpoint`() {
        val view = PreviewGeometry.viewPoint(PreviewView.SIDE, layout, 6.0)!!
        assertThat(view.position.x).isCloseTo(15.0, Offset.offset(1e-9))
        assertThat(Math.abs(view.position.z - 10.0)).isCloseTo(6.0, Offset.offset(1e-9))
        assertThat(view.position.y).isEqualTo(64.0)
        assertThat(view.lookAt).isEqualTo(Vec3(15.0, 65.0, 10.0))
    }

    @Test
    fun `opponent camera stands at the target looking at the caster`() {
        val view = PreviewGeometry.viewPoint(PreviewView.OPPONENT, layout, 6.0)!!
        assertThat(view.position).isEqualTo(layout.target)
        assertThat(view.lookAt).isEqualTo(Vec3(13.0, 65.0, 10.0))
        // Looking back along -X: yaw 90 degrees (Minecraft: +X is -90, -X is +90).
        assertThat(view.yaw).isCloseTo(90f, Offset.offset(0.5f))
    }

    @Test
    fun `behind camera is 4 blocks behind the caster, over a shoulder, aimed at the target`() {
        val view = PreviewGeometry.viewPoint(PreviewView.BEHIND, layout, 6.0)!!
        assertThat(view.position.x).isCloseTo(9.0, Offset.offset(1e-9))
        assertThat(Math.abs(view.position.z - 10.0)).isCloseTo(PreviewGeometry.SHOULDER_OFFSET, Offset.offset(1e-9))
        assertThat(view.position.y).isCloseTo(64.6, Offset.offset(1e-9))
        assertThat(view.lookAt).isEqualTo(Vec3(17.0, 65.0, 10.0))
        assertThat(view.yaw).isCloseTo(-90f, Offset.offset(8f))
    }

    @Test
    fun `stay has no camera`() {
        assertThat(PreviewGeometry.viewPoint(PreviewView.STAY, layout, 6.0)).isNull()
    }

    @Test
    fun `yaw and pitch follow Minecraft conventions`() {
        assertThat(PreviewGeometry.yawOf(Vec3(0.0, 0.0, 1.0))).isCloseTo(0f, Offset.offset(1e-4f))
        assertThat(PreviewGeometry.yawOf(Vec3(-1.0, 0.0, 0.0))).isCloseTo(90f, Offset.offset(1e-4f))
        assertThat(PreviewGeometry.pitchOf(Vec3(0.0, -1.0, 1.0))).isCloseTo(45f, Offset.offset(1e-4f))
        assertThat(PreviewGeometry.pitchOf(Vec3(0.0, 1.0, 1.0))).isCloseTo(-45f, Offset.offset(1e-4f))
    }

    @Test
    fun `options parse key=value tokens and reject bad input`() {
        val ok = PreviewOptions.parse("view=behind speed=0.5 distance=8") as Result.Success
        assertThat(ok.value).isEqualTo(PreviewOptions(PreviewView.BEHIND, 0.5, 8.0))
        assertThat((PreviewOptions.parse(null) as Result.Success).value).isEqualTo(PreviewOptions())
        assertThat((PreviewOptions.parse("speed=2") as Result.Failure).error)
            .isEqualTo(PreviewOptionsError.BadValue("speed", "2"))
        assertThat((PreviewOptions.parse("view=up") as Result.Failure).error)
            .isEqualTo(PreviewOptionsError.BadValue("view", "up"))
        assertThat((PreviewOptions.parse("fast") as Result.Failure).error)
            .isEqualTo(PreviewOptionsError.UnknownOption("fast"))
    }
}
