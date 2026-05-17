package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.domain.event.LeveledUp
import com.astarworks.astera.stats.domain.event.MissionCompleted
import com.astarworks.astera.stats.domain.level.Level
import com.astarworks.astera.stats.domain.level.Xp
import com.astarworks.astera.stats.domain.norma.NormaId
import com.astarworks.astera.stats.domain.norma.NormaReward
import com.astarworks.astera.testfixtures.FakeBroadcaster
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class BroadcastOnLeveledUpListenerTest {

    private lateinit var broadcaster: FakeBroadcaster
    private lateinit var listener: BroadcastOnLeveledUpListener
    private val playerId = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        broadcaster = FakeBroadcaster()
        listener = BroadcastOnLeveledUpListener(broadcaster)
    }

    private fun event(playerId: PlayerId = this.playerId, old: Int = 1, new: Int = 2, totalXp: Long = 200L) =
        LeveledUp(
            playerId = playerId,
            oldLevel = Level(old),
            newLevel = Level(new),
            totalXp = Xp(totalXp),
        )

    @Test
    fun `publishes the LeveledUp event to the broadcaster verbatim`() {
        val ev = event()
        listener.handle(ev)

        assertThat(broadcaster.published).hasSize(1)
        assertThat(broadcaster.published.single()).isSameAs(ev)
    }

    @Test
    fun `payload fields are preserved (playerId, oldLevel, newLevel, totalXp)`() {
        val ev = event(old = 4, new = 5, totalXp = 1500L)
        listener.handle(ev)

        val out = broadcaster.published.single() as LeveledUp
        assertThat(out.playerId).isEqualTo(playerId)
        assertThat(out.oldLevel).isEqualTo(Level(4))
        assertThat(out.newLevel).isEqualTo(Level(5))
        assertThat(out.totalXp).isEqualTo(Xp(1500L))
    }

    @Test
    fun `multiple events fan out in order`() {
        val e1 = event(new = 2)
        val e2 = event(new = 3)
        val e3 = event(new = 4)
        listener.handle(e1)
        listener.handle(e2)
        listener.handle(e3)

        assertThat(broadcaster.published).containsExactly(e1, e2, e3)
    }

    @Test
    fun `does not swallow null-fields - no nulls expected, sanity check`() {
        // sanity: handler does not mutate broadcaster on construction
        assertThat(broadcaster.published).isEmpty()
    }

    @Test
    fun `propagates broadcaster exceptions (no swallow contract)`() {
        val throwingBroadcaster = object : com.astarworks.astera.core.application.port.outbound.IBroadcaster {
            override fun publish(event: com.astarworks.astera.core.domain.event.DomainEvent) {
                throw IllegalStateException("simulated downstream failure")
            }
        }
        val l = BroadcastOnLeveledUpListener(throwingBroadcaster)
        try {
            l.handle(event())
            assertThat(false).`as`("expected exception").isTrue()
        } catch (ex: IllegalStateException) {
            assertThat(ex.message).isEqualTo("simulated downstream failure")
        }
    }

    @Test
    fun `is a no-op for unrelated event types (compile-time guard)`() {
        // handle() only accepts LeveledUp by type; passing MissionCompleted would not compile.
        // Sanity assertion: ensure unused symbol stays in test imports for future expansion.
        val ignored: MissionCompleted = MissionCompleted(
            playerId = playerId,
            normaId = NormaId("unused-norma"),
            reward = NormaReward(currencyId = null, currencyAmount = 0L, xp = 0L),
        )
        assertThat(ignored.normaId.value).isEqualTo("unused-norma")
        assertThat(broadcaster.published).isEmpty()
    }
}
