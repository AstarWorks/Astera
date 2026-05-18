package com.astarworks.astera.match.domain.mode

import com.astarworks.astera.core.domain.match.TeamId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RegionCaptureProgressTest {

    private val regionId = "wool_zone_a"
    private val red = TeamId("red")
    private val blue = TeamId("blue")

    @Test
    fun `empty creates a zeroed progress for the region`() {
        val p = RegionCaptureProgress.empty(regionId)

        assertThat(p.regionId).isEqualTo(regionId)
        assertThat(p.occupyingTeam).isNull()
        assertThat(p.accumulatedTicks).isEqualTo(0L)
        assertThat(p.capturedBy).isNull()
    }

    @Test
    fun `withTick increments accumulated when same team keeps occupying`() {
        val start = RegionCaptureProgress.empty(regionId)

        val t1 = start.withTick(red, ticksRequired = 5L)
        val t2 = t1.withTick(red, ticksRequired = 5L)
        val t3 = t2.withTick(red, ticksRequired = 5L)

        assertThat(t1.accumulatedTicks).isEqualTo(1L)
        assertThat(t1.occupyingTeam).isEqualTo(red)
        assertThat(t1.capturedBy).isNull()
        assertThat(t3.accumulatedTicks).isEqualTo(3L)
        assertThat(t3.capturedBy).isNull()
    }

    @Test
    fun `withTick reaches capture once accumulated meets ticksRequired`() {
        var p = RegionCaptureProgress.empty(regionId)
        repeat(5) { p = p.withTick(red, ticksRequired = 5L) }

        assertThat(p.accumulatedTicks).isEqualTo(5L)
        assertThat(p.capturedBy).isEqualTo(red)
        assertThat(p.occupyingTeam).isEqualTo(red)
    }

    @Test
    fun `withTick after capture is a no-op (locked region)`() {
        var p = RegionCaptureProgress.empty(regionId)
        repeat(5) { p = p.withTick(red, ticksRequired = 5L) }
        val locked = p

        val again = locked.withTick(blue, ticksRequired = 5L)
        val empty = locked.withTick(null, ticksRequired = 5L)

        assertThat(again).isEqualTo(locked)
        assertThat(empty).isEqualTo(locked)
    }

    @Test
    fun `withTick resets accumulated when occupant becomes null`() {
        var p = RegionCaptureProgress.empty(regionId)
        repeat(3) { p = p.withTick(red, ticksRequired = 10L) }
        assertThat(p.accumulatedTicks).isEqualTo(3L)

        val after = p.withTick(null, ticksRequired = 10L)

        assertThat(after.occupyingTeam).isNull()
        assertThat(after.accumulatedTicks).isEqualTo(0L)
        assertThat(after.capturedBy).isNull()
    }

    @Test
    fun `withTick restarts at 1 when occupant changes mid-capture`() {
        var p = RegionCaptureProgress.empty(regionId)
        repeat(4) { p = p.withTick(red, ticksRequired = 10L) }
        assertThat(p.accumulatedTicks).isEqualTo(4L)

        val flipped = p.withTick(blue, ticksRequired = 10L)

        assertThat(flipped.occupyingTeam).isEqualTo(blue)
        assertThat(flipped.accumulatedTicks).isEqualTo(1L)
        assertThat(flipped.capturedBy).isNull()
    }

    @Test
    fun `withTick is idempotent on a long-empty progress`() {
        val empty = RegionCaptureProgress.empty(regionId)

        val a = empty.withTick(null, ticksRequired = 5L)
        val b = a.withTick(null, ticksRequired = 5L)

        assertThat(a).isEqualTo(empty)
        assertThat(b).isEqualTo(empty)
    }
}
