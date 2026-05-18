package com.astarworks.astera.match.application.usecase.zone

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.match.domain.zone.CaptureZone
import com.astarworks.astera.match.domain.zone.CaptureZoneId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wave 5d [CaptureZoneTickUseCase] state-machine tests.
 *
 * Covers every branch of the §"Rules" section in the use case's KDoc:
 * empty / contested / owning-team-tops-off / neutral-being-captured /
 * neutral-finishes-capture / opposing-drains / opposing-fully-neutralizes.
 */
internal class CaptureZoneTickUseCaseTest {

    private val matchId = MatchId("match-1")
    private val region = Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 5.0, 10.0))
    private val red = TeamId("red")
    private val blue = TeamId("blue")
    private val useCase = CaptureZoneTickUseCase()

    @Test
    fun `empty zone produces no change`() {
        val zone = zone(progress = 30, owner = red)
        val next = useCase.tick(zone, emptyMap())
        assertThat(next).isSameAs(zone)
    }

    @Test
    fun `zone whose occupancy map has only zero counts is treated as empty`() {
        val zone = zone(progress = 30, owner = red)
        val next = useCase.tick(zone, mapOf(red to 0, blue to 0))
        assertThat(next).isSameAs(zone)
    }

    @Test
    fun `contested zone with two teams produces no change`() {
        val zone = zone(progress = 30, owner = null)
        val next = useCase.tick(zone, mapOf(red to 2, blue to 1))
        assertThat(next).isSameAs(zone)
    }

    @Test
    fun `neutral zone increments progress for a single team`() {
        val zone = zone(progress = 0, owner = null, rate = 10)
        val next = useCase.tick(zone, mapOf(red to 1))
        assertThat(next.progress).isEqualTo(10)
        assertThat(next.currentOwner).isNull()
    }

    @Test
    fun `neutral zone transfers ownership when progress hits MAX`() {
        val zone = zone(progress = 95, owner = null, rate = 10)
        val next = useCase.tick(zone, mapOf(red to 1))
        assertThat(next.progress).isEqualTo(CaptureZone.MAX_PROGRESS)
        assertThat(next.currentOwner).isEqualTo(red)
    }

    @Test
    fun `neutral zone clamps progress at MAX on overflow`() {
        // rate larger than the remaining gap: must clamp, not overflow.
        val zone = zone(progress = 95, owner = null, rate = 50)
        val next = useCase.tick(zone, mapOf(red to 1))
        assertThat(next.progress).isEqualTo(CaptureZone.MAX_PROGRESS)
        assertThat(next.currentOwner).isEqualTo(red)
    }

    @Test
    fun `owning team caps progress at MAX without changing owner`() {
        val zone = zone(progress = 70, owner = red, rate = 10)
        val next = useCase.tick(zone, mapOf(red to 1))
        assertThat(next.progress).isEqualTo(CaptureZone.MAX_PROGRESS)
        assertThat(next.currentOwner).isEqualTo(red)
    }

    @Test
    fun `owning team at full progress produces no change`() {
        val zone = zone(progress = CaptureZone.MAX_PROGRESS, owner = red, rate = 10)
        val next = useCase.tick(zone, mapOf(red to 3))
        assertThat(next).isSameAs(zone)
    }

    @Test
    fun `opposing team drains progress without changing owner mid-drain`() {
        val zone = zone(progress = 50, owner = red, rate = 10)
        val next = useCase.tick(zone, mapOf(blue to 1))
        assertThat(next.progress).isEqualTo(40)
        assertThat(next.currentOwner).isEqualTo(red)
    }

    @Test
    fun `opposing team fully drains progress and zone goes neutral`() {
        val zone = zone(progress = 10, owner = red, rate = 10)
        val next = useCase.tick(zone, mapOf(blue to 1))
        assertThat(next.progress).isZero
        assertThat(next.currentOwner).isNull()
    }

    @Test
    fun `opposing team drain clamps at zero on overflow`() {
        val zone = zone(progress = 5, owner = red, rate = 50)
        val next = useCase.tick(zone, mapOf(blue to 1))
        assertThat(next.progress).isZero
        assertThat(next.currentOwner).isNull()
    }

    @Test
    fun `neutralizing tick does NOT begin opposing capture in the same tick`() {
        // Wave 5d invariant: the tick that drives progress to 0 only clears
        // ownership; the *next* scheduler tick begins the takeover. This
        // prevents a single tick from both flipping the meter and granting
        // partial new-team progress.
        val zone = zone(progress = 5, owner = red, rate = 50)
        val next = useCase.tick(zone, mapOf(blue to 1))
        assertThat(next.progress).isZero
        assertThat(next.currentOwner).isNull()

        // Subsequent tick — now neutral, blue is the only occupant — begins
        // capture from zero.
        val after = useCase.tick(next, mapOf(blue to 1))
        assertThat(after.progress).isEqualTo(50)
        assertThat(after.currentOwner).isNull()
    }

    @Test
    fun `tick is pure - input zone is not mutated`() {
        val zone = zone(progress = 30, owner = null, rate = 10)
        useCase.tick(zone, mapOf(red to 1))
        assertThat(zone.progress).isEqualTo(30)
        assertThat(zone.currentOwner).isNull()
    }

    private fun zone(
        progress: Int,
        owner: TeamId?,
        rate: Int = 10,
    ): CaptureZone = CaptureZone(
        id = CaptureZoneId("z-1"),
        matchId = matchId,
        region = region,
        currentOwner = owner,
        progress = progress,
        captureRatePerTick = rate,
    )
}
