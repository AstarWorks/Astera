package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryCooldownTrackerTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val otherPlayer = PlayerId(UUID.randomUUID())

    private lateinit var scheduler: FakeScheduler
    private lateinit var tracker: InMemoryCooldownTracker

    @BeforeEach
    fun setUp() {
        scheduler = FakeScheduler(startTick = 0L)
        tracker = InMemoryCooldownTracker(scheduler)
    }

    // -------------------------------------------------------------------------
    // isCoolingDown / remainingTicks
    // -------------------------------------------------------------------------

    @Test
    fun `not on cooldown initially`() {
        val key = CooldownKey.Weapon("iron-sword")
        assertThat(tracker.isCoolingDown(playerId, key)).isFalse()
        assertThat(tracker.remainingTicks(playerId, key)).isEqualTo(0L)
    }

    @Test
    fun `isCoolingDown true immediately after start`() {
        val key = CooldownKey.Weapon("iron-sword")
        tracker.start(playerId, key, 20L)
        assertThat(tracker.isCoolingDown(playerId, key)).isTrue()
        assertThat(tracker.remainingTicks(playerId, key)).isEqualTo(20L)
    }

    @Test
    fun `remainingTicks decreases as ticks advance`() {
        val key = CooldownKey.Skill("fireball")
        tracker.start(playerId, key, 40L)

        scheduler.advanceTicks(10)
        assertThat(tracker.remainingTicks(playerId, key)).isEqualTo(30L)

        scheduler.advanceTicks(30)
        assertThat(tracker.remainingTicks(playerId, key)).isEqualTo(0L)
        assertThat(tracker.isCoolingDown(playerId, key)).isFalse()
    }

    @Test
    fun `start rejects negative durationTicks`() {
        val key = CooldownKey.Custom("test", "x")
        assertThatIllegalArgumentException()
            .isThrownBy { tracker.start(playerId, key, -1L) }
    }

    // -------------------------------------------------------------------------
    // reset
    // -------------------------------------------------------------------------

    @Test
    fun `reset clears active cooldown`() {
        val key = CooldownKey.Status("burn")
        tracker.start(playerId, key, 100L)
        assertThat(tracker.isCoolingDown(playerId, key)).isTrue()

        tracker.reset(playerId, key)
        assertThat(tracker.isCoolingDown(playerId, key)).isFalse()
    }

    @Test
    fun `reset is no-op when not on cooldown`() {
        val key = CooldownKey.Weapon("fire-sword")
        tracker.reset(playerId, key) // must not throw
        assertThat(tracker.isCoolingDown(playerId, key)).isFalse()
    }

    // -------------------------------------------------------------------------
    // tick
    // -------------------------------------------------------------------------

    @Test
    fun `tick returns empty when nothing expired`() {
        val key = CooldownKey.Weapon("iron-sword")
        tracker.start(playerId, key, 20L) // expires at tick 20

        // advance to tick 19 — not yet expired
        scheduler.advanceTicks(19)
        val expired = tracker.tick(scheduler.currentTick())
        assertThat(expired).isEmpty()
    }

    @Test
    fun `tick returns expired entry and removes it`() {
        val key = CooldownKey.Weapon("iron-sword")
        tracker.start(playerId, key, 20L) // expires at tick 20

        scheduler.advanceTicks(20)
        val expired = tracker.tick(scheduler.currentTick())
        assertThat(expired).containsExactly(Pair(playerId, key))

        // no longer tracked
        assertThat(tracker.isCoolingDown(playerId, key)).isFalse()
    }

    @Test
    fun `tick handles multiple independent keys`() {
        val weaponKey = CooldownKey.Weapon("sword")
        val skillKey = CooldownKey.Skill("fireball")
        val statusKey = CooldownKey.Status("burn")

        tracker.start(playerId, weaponKey, 10L)  // expires tick 10
        tracker.start(playerId, skillKey, 20L)   // expires tick 20
        tracker.start(otherPlayer, statusKey, 10L) // expires tick 10

        scheduler.advanceTicks(10)
        val expired = tracker.tick(scheduler.currentTick())

        assertThat(expired).containsExactlyInAnyOrder(
            Pair(playerId, weaponKey),
            Pair(otherPlayer, statusKey),
        )
        // skillKey still active
        assertThat(tracker.isCoolingDown(playerId, skillKey)).isTrue()
    }

    @Test
    fun `tick with no active cooldowns returns empty`() {
        assertThat(tracker.tick(100L)).isEmpty()
    }

    // -------------------------------------------------------------------------
    // activeKeysFor
    // -------------------------------------------------------------------------

    @Test
    fun `activeKeysFor returns only non-expired keys for the player`() {
        val weaponKey = CooldownKey.Weapon("sword")
        val skillKey = CooldownKey.Skill("fireball")

        tracker.start(playerId, weaponKey, 10L)
        tracker.start(playerId, skillKey, 30L)
        tracker.start(otherPlayer, CooldownKey.Weapon("other-sword"), 10L)

        scheduler.advanceTicks(15) // weaponKey expired, skillKey active
        // Note: expired entries remain until tick() cleans them up.
        // remainingTicks returns 0 for expired entries; activeKeysFor filters > 0.
        assertThat(tracker.activeKeysFor(playerId)).containsExactly(skillKey)
    }

    @Test
    fun `activeKeysFor returns empty when no active cooldowns`() {
        assertThat(tracker.activeKeysFor(playerId)).isEmpty()
    }

    // -------------------------------------------------------------------------
    // CooldownKey discriminator
    // -------------------------------------------------------------------------

    @Test
    fun `Weapon and Skill keys with same string are independent`() {
        val weaponKey = CooldownKey.Weapon("fireball")
        val skillKey = CooldownKey.Skill("fireball")

        tracker.start(playerId, weaponKey, 10L)
        assertThat(tracker.isCoolingDown(playerId, weaponKey)).isTrue()
        assertThat(tracker.isCoolingDown(playerId, skillKey)).isFalse()
    }
}
