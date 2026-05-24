package com.astarworks.astera.core.domain.survival

import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GatheringProgressTest {

    private val now = Instant.parse("2026-05-24T12:00:00Z")
    private val playerId = PlayerId(UUID.randomUUID())
    private val spotId = GatheringSpotId("iron-vein")

    @Test
    fun `first() initialises with totalHarvests=1`() {
        val p = GatheringProgress.first(playerId, spotId, now)
        assertThat(p.totalHarvests).isEqualTo(1L)
        assertThat(p.lastHarvestedAt).isEqualTo(now)
    }

    @Test
    fun `recordHarvest increments and bumps timestamp`() {
        val initial = GatheringProgress.first(playerId, spotId, now)
        val later = now.plusSeconds(60)
        val updated = initial.recordHarvest(later)
        assertThat(updated.totalHarvests).isEqualTo(2L)
        assertThat(updated.lastHarvestedAt).isEqualTo(later)
    }

    @Test
    fun `isOffCooldown false when within cooldown window`() {
        val p = GatheringProgress.first(playerId, spotId, now)
        // 100 ticks = 5 seconds; query at +2 seconds.
        assertThat(p.isOffCooldown(now.plusMillis(2_000), cooldownTicks = 100L)).isFalse
    }

    @Test
    fun `isOffCooldown true after cooldown elapses`() {
        val p = GatheringProgress.first(playerId, spotId, now)
        // 100 ticks = 5 seconds; query at +5 seconds.
        assertThat(p.isOffCooldown(now.plusMillis(5_000), cooldownTicks = 100L)).isTrue
    }

    @Test
    fun `negative totalHarvests rejected`() {
        assertThatThrownBy {
            GatheringProgress(
                playerId = playerId,
                spotId = spotId,
                lastHarvestedAt = now,
                totalHarvests = -1L,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
