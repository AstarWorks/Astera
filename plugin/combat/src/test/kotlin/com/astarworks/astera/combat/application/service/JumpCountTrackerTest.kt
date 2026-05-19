package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class JumpCountTrackerTest {

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `jumpsThisGround starts at zero for an unseen player`() {
        val tracker = JumpCountTracker()
        assertThat(tracker.jumpsThisGround(playerId())).isZero
    }

    @Test
    fun `recordJump returns the running total`() {
        val tracker = JumpCountTracker()
        val id = playerId()

        assertThat(tracker.recordJump(id)).isEqualTo(1)
        assertThat(tracker.recordJump(id)).isEqualTo(2)
        assertThat(tracker.jumpsThisGround(id)).isEqualTo(2)
    }

    @Test
    fun `resetOnGround clears the counter for one player only`() {
        val tracker = JumpCountTracker()
        val a = playerId()
        val b = playerId()
        tracker.recordJump(a)
        tracker.recordJump(a)
        tracker.recordJump(b)

        tracker.resetOnGround(a)

        assertThat(tracker.jumpsThisGround(a)).isZero
        assertThat(tracker.jumpsThisGround(b)).isEqualTo(1)
    }

    @Test
    fun `resetOnGround on an unseen player is a no-op`() {
        val tracker = JumpCountTracker()
        tracker.resetOnGround(playerId())
        // Must not throw.
    }

    @Test
    fun `after reset, a fresh jump starts back at one`() {
        val tracker = JumpCountTracker()
        val id = playerId()
        tracker.recordJump(id)
        tracker.recordJump(id)
        tracker.resetOnGround(id)

        assertThat(tracker.recordJump(id)).isEqualTo(1)
    }
}
