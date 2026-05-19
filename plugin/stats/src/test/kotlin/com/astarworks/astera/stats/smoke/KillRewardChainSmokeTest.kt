package com.astarworks.astera.stats.smoke

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.eventbus.listeners.MissionProgressOnKillListener
import com.astarworks.astera.stats.application.eventbus.listeners.RewardOnKillListener
import com.astarworks.astera.stats.application.service.InMemoryLevelService
import com.astarworks.astera.stats.application.service.InMemoryNormaService
import com.astarworks.astera.stats.application.service.InMemoryPlayerStatsRepository
import com.astarworks.astera.stats.application.usecase.level.GrantXpUseCase
import com.astarworks.astera.stats.application.usecase.stats.RecordKillUseCase
import com.astarworks.astera.stats.domain.event.XpGranted
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * End-to-end: Combat publishes PlayerKilled → Stats listener subscribes via the
 * real [InMemoryDomainEventBus] → invokes RecordKillUseCase + GrantXpUseCase
 * → GrantXp publishes XpGranted. Asserts:
 *   - attacker's kill count incremented
 *   - attacker's XP/level updated
 *   - XpGranted event observed on the bus (the cross-plugin hook Economy
 *     subscribes to via DepositOnXpListener — that side is covered by
 *     DepositOnXpListenerTest in plugin/economy).
 *
 * This split smoke test honors Konsist: plugin/stats can import combat events
 * (line 344-356 only bans economy/persistence); plugin/economy cannot import
 * combat, so the chain is validated in two hops rather than one.
 */
class KillRewardChainSmokeTest {

    @Test
    fun `PlayerKilled drives kill stat increment and XpGranted publication`() {
        val bus = InMemoryDomainEventBus()

        val statsRepo = InMemoryPlayerStatsRepository()
        val levelService = InMemoryLevelService()
        val normaService = InMemoryNormaService()

        val recordKill = RecordKillUseCase(statsRepo, bus)
        val grantXp = GrantXpUseCase(levelService, bus)

        val rewardOnKill = RewardOnKillListener(recordKill, grantXp)
        val missionProgressOnKill = MissionProgressOnKillListener(normaService, bus)

        bus.subscribe(PlayerKilled::class) { rewardOnKill.handle(it) }
        bus.subscribe(PlayerKilled::class) { missionProgressOnKill.handle(it) }

        val capturedXp = mutableListOf<XpGranted>()
        bus.subscribe(XpGranted::class) { capturedXp += it }

        val attacker = PlayerId.parse("00000000-0000-0000-0000-0000000000aa")
        val victim = PlayerId.parse("00000000-0000-0000-0000-0000000000bb")

        bus.publish(
            PlayerKilled(
                victim = victim,
                killer = attacker,
                source = DamageSource.Item(ItemId("smoke-sword"), attacker),
                atTick = 0L,
            ),
        )

        // Stat side: attacker kill count incremented
        val attackerStats = statsRepo.find(attacker)
        assertThat(attackerStats).isNotNull
        assertThat(attackerStats!!.kills).isEqualTo(1L)

        // Level side: XP and (possibly) level changed
        assertThat(levelService.totalXp(attacker).value).isGreaterThan(0L)

        // Event side: XpGranted observable on the bus (the hand-off point to
        // Economy.DepositOnXpListener).
        assertThat(capturedXp).hasSize(1)
        assertThat(capturedXp.single().playerId).isEqualTo(attacker)
        assertThat(capturedXp.single().amount).isGreaterThan(0L)
    }
}
