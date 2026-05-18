package com.astarworks.astera.match.domain.zone

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.Region
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Wave 5d [CaptureZone] / [CaptureZoneId] invariant tests.
 *
 * The use-case-level state machine lives in
 * `application.usecase.zone.CaptureZoneTickUseCaseTest`; here we pin the
 * value-object invariants (id format, progress bounds, capture rate bounds).
 */
internal class CaptureZoneTest {

    private val matchId = MatchId("match-1")
    private val region = Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 5.0, 10.0))

    @Test
    fun `progress at zero is allowed`() {
        val zone = zone(progress = 0)
        assertThat(zone.progress).isZero
    }

    @Test
    fun `progress at MAX_PROGRESS is allowed`() {
        val zone = zone(progress = CaptureZone.MAX_PROGRESS)
        assertThat(zone.progress).isEqualTo(CaptureZone.MAX_PROGRESS)
    }

    @Test
    fun `progress above MAX_PROGRESS is rejected`() {
        assertThatThrownBy { zone(progress = CaptureZone.MAX_PROGRESS + 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `negative progress is rejected`() {
        assertThatThrownBy { zone(progress = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `captureRatePerTick at 1 is allowed`() {
        val zone = zone(rate = 1)
        assertThat(zone.captureRatePerTick).isEqualTo(1)
    }

    @Test
    fun `captureRatePerTick at MAX_PROGRESS is allowed`() {
        val zone = zone(rate = CaptureZone.MAX_PROGRESS)
        assertThat(zone.captureRatePerTick).isEqualTo(CaptureZone.MAX_PROGRESS)
    }

    @Test
    fun `captureRatePerTick of zero is rejected`() {
        assertThatThrownBy { zone(rate = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `captureRatePerTick above MAX_PROGRESS is rejected`() {
        assertThatThrownBy { zone(rate = CaptureZone.MAX_PROGRESS + 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `MAX_PROGRESS is 100`() {
        assertThat(CaptureZone.MAX_PROGRESS).isEqualTo(100)
    }

    @Test
    fun `blank zone id is rejected`() {
        assertThatThrownBy { CaptureZoneId("") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { CaptureZoneId("   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `non-blank zone id round-trips through toString`() {
        val id = CaptureZoneId("arena-center")
        assertThat(id.toString()).isEqualTo("arena-center")
        assertThat(id.value).isEqualTo("arena-center")
    }

    private fun zone(
        progress: Int = 0,
        rate: Int = 5,
        owner: TeamId? = null,
    ): CaptureZone = CaptureZone(
        id = CaptureZoneId("z-1"),
        matchId = matchId,
        region = region,
        currentOwner = owner,
        progress = progress,
        captureRatePerTick = rate,
    )
}
