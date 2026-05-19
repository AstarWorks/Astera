package com.astarworks.astera.stats.scenario

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.stats.domain.event.XpGranted
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wave _infra-1 canonical scenario #1.
 *
 * Attacker kills target with `amethyst-sword`; reward listeners must fire
 * (kill recorded, XP granted, XpGranted event published). Exercises the
 * RewardOnKillListener wiring end-to-end.
 */
class AmethystSwordKillsTargetScenarioTest {

    @Test
    fun `amethyst-sword kill increments stats and grants XP`() {
        val sword = ItemId("amethyst-sword")
        val ctx = scenario {
            val attacker = player("alice")
            val target = player("bob")
            kills(attacker, target, with = sword)

            shouldBeDead(target)
            shouldHaveXp(attacker, atLeast = 1L)

            assertThat(harness.playerStatsRepo.find(attacker.id)?.kills).isEqualTo(1L)
        }

        assertThat(ctx.published<PlayerKilled>()).hasSize(1)
        assertThat(ctx.published<XpGranted>()).hasSize(1)
    }
}
