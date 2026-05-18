package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.match.domain.zone.CaptureZone
import com.astarworks.astera.match.domain.zone.CaptureZoneId
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Wave 5d [CaptureZoneScheduler] integration tests.
 *
 * Uses [FakeScheduler] to drive the repeating loop deterministically.
 * Verifies cadence (period = 20 ticks default), onAdvance invocation only
 * when state changes, and that the scheduler reads zones/lookup fresh on
 * each tick.
 */
internal class CaptureZoneSchedulerTest {

    private val matchId = MatchId("match-1")
    private val region = Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 5.0, 10.0))
    private val red = TeamId("red")
    private val blue = TeamId("blue")

    @Test
    fun `scheduler advances a single zone once per default period`() {
        val fake = FakeScheduler()
        val state = mutableListOf(neutralZone(rate = 10))
        val advances = mutableListOf<CaptureZone>()

        val sut = CaptureZoneScheduler(
            zones = { state.toList() },
            occupancyLookup = { mapOf(red to 1) },
            onAdvance = { z ->
                advances += z
                // Mimic Phase C wiring: persist the advanced state so the
                // next tick reads the new progress.
                state[0] = z
            },
            scheduler = fake,
        )
        val handle = sut.start()

        // Period is 20 ticks; advance exactly 100 ticks → 5 fires.
        fake.advanceTicks(100L)

        assertThat(advances.map { it.progress })
            .containsExactly(10, 20, 30, 40, 50)
        assertThat(advances.last().currentOwner).isNull()

        handle.cancel()
    }

    @Test
    fun `scheduler does not invoke onAdvance when the zone is unchanged`() {
        val fake = FakeScheduler()
        val zone = neutralZone(rate = 10)
        val advances = mutableListOf<CaptureZone>()

        val sut = CaptureZoneScheduler(
            zones = { listOf(zone) },
            // Contested → tick never produces a delta.
            occupancyLookup = { mapOf(red to 1, blue to 1) },
            onAdvance = { advances += it },
            scheduler = fake,
        )
        val handle = sut.start()

        fake.advanceTicks(200L)

        assertThat(advances).isEmpty()
        handle.cancel()
    }

    @Test
    fun `scheduler reads the zone list fresh on each tick`() {
        val fake = FakeScheduler()
        val all = mutableListOf(neutralZone(id = "z-1", rate = 10))
        val advances = mutableListOf<CaptureZoneId>()

        val sut = CaptureZoneScheduler(
            zones = { all.toList() },
            occupancyLookup = { mapOf(red to 1) },
            onAdvance = { advances += it.id },
            scheduler = fake,
        )
        val handle = sut.start()

        fake.advanceTicks(20L)
        assertThat(advances.map { it.value }).containsExactly("z-1")

        // Add a new zone concurrently.
        all += neutralZone(id = "z-2", rate = 10)
        fake.advanceTicks(20L)

        assertThat(advances.map { it.value }).containsExactly("z-1", "z-1", "z-2")
        handle.cancel()
    }

    @Test
    fun `scheduler respects an overridden periodTicks`() {
        val fake = FakeScheduler()
        val state = mutableListOf(neutralZone(rate = 10))
        val advances = mutableListOf<CaptureZone>()

        val sut = CaptureZoneScheduler(
            zones = { state.toList() },
            occupancyLookup = { mapOf(red to 1) },
            onAdvance = { state[0] = it; advances += it },
            scheduler = fake,
            periodTicks = 5L,
        )
        val handle = sut.start()

        // 5-tick period × 4 fires = 20 ticks elapsed.
        fake.advanceTicks(20L)
        assertThat(advances.map { it.progress })
            .containsExactly(10, 20, 30, 40)

        handle.cancel()
    }

    @Test
    fun `scheduler rejects periodTicks below 1`() {
        val fake = FakeScheduler()
        assertThatThrownBy {
            CaptureZoneScheduler(
                zones = { emptyList() },
                occupancyLookup = { emptyMap() },
                onAdvance = { },
                scheduler = fake,
                periodTicks = 0L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `cancelled handle halts further onAdvance invocations`() {
        val fake = FakeScheduler()
        val state = mutableListOf(neutralZone(rate = 10))
        val advances = mutableListOf<CaptureZone>()

        val sut = CaptureZoneScheduler(
            zones = { state.toList() },
            occupancyLookup = { mapOf(red to 1) },
            onAdvance = { state[0] = it; advances += it },
            scheduler = fake,
        )
        val handle = sut.start()

        fake.advanceTicks(20L)
        assertThat(advances).hasSize(1)

        handle.cancel()
        fake.advanceTicks(200L)
        assertThat(advances).hasSize(1)
    }

    private fun neutralZone(
        id: String = "z-1",
        rate: Int = 5,
    ): CaptureZone = CaptureZone(
        id = CaptureZoneId(id),
        matchId = matchId,
        region = region,
        currentOwner = null,
        progress = 0,
        captureRatePerTick = rate,
    )
}
