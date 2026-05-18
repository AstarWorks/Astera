package com.astarworks.astera.stats.scenario

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.norma.Norma
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.norma.NormaReward
import com.astarworks.astera.core.domain.norma.NormaTarget
import com.astarworks.astera.core.domain.norma.PeriodKind
import com.astarworks.astera.weapons.domain.weapon.WeaponId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wave _infra-1 canonical scenario #5.
 *
 * Register a `Kills` norma; publish a [com.astarworks.astera.combat.domain.event.PlayerKilled]
 * via the DSL; assert the player's progress on that norma incremented via
 * [com.astarworks.astera.stats.application.eventbus.listeners.MissionProgressOnKillListener].
 */
class NormaProgressOnObjectiveScenarioTest {

    @Test
    fun `kill ticks progress on an active Kills norma`() {
        val sword = WeaponId("amethyst-sword")
        scenario {
            val attacker = player("alice")
            val target = player("bob")

            val norma = Norma(
                id = NormaId("daily-2-kills"),
                target = NormaTarget.Kills(count = 2),
                reward = NormaReward(currencyId = "STAR", currencyAmount = 10L, xp = 50L),
                period = PeriodKind.Daily,
                displayKey = MessageKey("astera.norma.daily-2-kills"),
            )
            harness.normaService.register(norma)

            kills(attacker, target, with = sword)

            val progress = harness.normaProgressRepo.find(attacker.id, norma.id)
            assertThat(progress)
                .withFailMessage { "expected progress entry for ${attacker.name} but found none" }
                .isNotNull()
            assertThat(progress!!.current).isEqualTo(1L)
        }
    }
}
