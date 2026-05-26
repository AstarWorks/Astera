package com.astarworks.astera.core.domain.cinematic

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CinematicPathTest {

    private fun wp(target: Vec3, durationTicks: Long, easing: Easing = Easing.LINEAR): CameraWaypoint =
        CameraWaypoint(
            target = CameraTarget.Position(target),
            durationTicksToNext = durationTicks,
            easing = easing,
        )

    @Test
    fun `single-waypoint path has zero duration`() {
        val path = CinematicPath(listOf(wp(Vec3(0.0, 0.0, 0.0), durationTicks = 999)))
        assertThat(path.totalDurationTicks).isZero()
    }

    @Test
    fun `multi-waypoint duration sums all but last`() {
        val path = CinematicPath(
            listOf(
                wp(Vec3(0.0, 0.0, 0.0), durationTicks = 20),
                wp(Vec3(1.0, 0.0, 0.0), durationTicks = 40),
                wp(Vec3(2.0, 0.0, 0.0), durationTicks = 100), // ignored — last
            ),
        )
        assertThat(path.totalDurationTicks).isEqualTo(60L)
    }

    @Test
    fun `empty waypoint list is rejected`() {
        assertThatThrownBy { CinematicPath(emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `negative durationTicksToNext is rejected at waypoint construction`() {
        assertThatThrownBy { wp(Vec3(0.0, 0.0, 0.0), durationTicks = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `path with all-zero durations totals zero`() {
        val path = CinematicPath(
            listOf(
                wp(Vec3(0.0, 0.0, 0.0), durationTicks = 0),
                wp(Vec3(1.0, 0.0, 0.0), durationTicks = 0),
            ),
        )
        assertThat(path.totalDurationTicks).isZero()
    }

    @Test
    fun `Easing reuse — Wave 5h enum entries are accepted`() {
        val path = CinematicPath(
            listOf(
                wp(Vec3(0.0, 0.0, 0.0), 20, Easing.EASE_IN_OUT_CUBIC),
                wp(Vec3(1.0, 0.0, 0.0), 20, Easing.EASE_OUT_BOUNCE),
            ),
        )
        assertThat(path.waypoints.map { it.easing })
            .containsExactly(Easing.EASE_IN_OUT_CUBIC, Easing.EASE_OUT_BOUNCE)
    }
}
