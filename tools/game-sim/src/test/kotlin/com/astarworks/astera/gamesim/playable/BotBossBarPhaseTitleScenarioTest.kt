package com.astarworks.astera.gamesim.playable

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A bot seat sees the match boss bar of the annihilation phase flow (ADR-0319) as plain text: the title the plugin's own
 * AnnihilationClarity path renders from the en i18n key (`astera.annihilation.bar`), captured by the sim's in-memory
 * IObjectiveStage. Phase 1 at the start, Phase 2 after the build -> engage boundary.
 */
class BotBossBarPhaseTitleScenarioTest {

    @Test
    fun `bossBarTitles shows the Phase 1 title at start and the Phase 2 title after the engage boundary`() {
        MatchDriver.boot(MatchConfig(mode = "annihilation", stage = "annihilation-arena", botsPerTeam = 1, seed = 20261101L)).use { driver ->
            val host = driver.hostOf(driver.seats.first())
            driver.step(60)
            val first = host.bossBarTitles
            println("BOSSBAR phase1: $first")
            assertThat(first).hasSize(1)
            assertThat(first.single()).matches("Phase 1: Preparation next in \\d+:\\d{2}")

            val engaged = driver.stepUntil(8_000) { host.bossBarTitles.any { it.startsWith("Phase 2:") } }
            assertThat(engaged).isTrue()
            driver.step(30)
            val second = host.bossBarTitles
            println("BOSSBAR phase2: $second")
            assertThat(second.single()).matches("Phase 2: Engage next in \\d+:\\d{2}")
        }
    }
}
