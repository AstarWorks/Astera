package com.astarworks.astera.gamesim.playable

import com.astarworks.astera.bots.ObservedModeFacts
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The annihilation phase a bot reads comes from the boss bar COLOUR (AnnihilationClarity.showBar: green / blue / yellow / red),
 * never from its i18n text. Ja-locale bots in a real match see Japanese titles and still detect Preparation then Engage; the
 * facts do not care what the title says; a bar without a phase colour fails loudly instead of falling back to the 360 s timer.
 */
class BotPhaseSignalLocaleScenarioTest {

    private fun facts() = ObservedModeFacts("annihilation", solidAt = { _, _, _ -> null }, pillarBlock = { _, _ -> null })

    @Test
    fun `ja-locale bots see Japanese bar text and the phase still comes from the bar colour`() {
        MatchDriver.boot(MatchConfig(mode = "annihilation", stage = "annihilation-arena", botsPerTeam = 1, seed = 20261101L, locale = "ja")).use { driver ->
            val host = driver.hostOf(driver.seats.first())
            driver.step(60)
            assertThat(host.bossBarTitles.single()).startsWith("フェーズ1").doesNotContain("Phase")
            assertThat(host.bossBarColors).containsExactly("GREEN")
            val probe = facts()
            probe.updateRainmaker(host.bossBarTitles, emptyList(), host.bossBarColors)
            assertThat(probe.phase()).`as`("Preparation under ja").isEqualTo(1)

            assertThat(driver.stepUntil(8_000) { host.bossBarColors.contains("BLUE") }).isTrue()
            driver.step(30)
            probe.updateRainmaker(host.bossBarTitles, emptyList(), host.bossBarColors)
            assertThat(probe.phase()).`as`("Engage under ja").isEqualTo(2)
            assertThat(driver.brainErrors).`as`("no brain tick failed (a broken phase signal throws)").isEmpty()
        }
    }

    @Test
    fun `the phase does not depend on the title text`() {
        val probe = facts()
        probe.updateRainmaker(listOf("zzz ???"), emptyList(), listOf("GREEN"))
        assertThat(probe.phase()).isEqualTo(1)
        probe.updateRainmaker(listOf("Phase 1: reworded"), emptyList(), listOf("YELLOW"))
        assertThat(probe.phase()).isEqualTo(3)
    }

    @Test
    fun `no bar seen means no phase (the caller timer), a bar without a phase colour fails loudly`() {
        val probe = facts()
        assertThat(probe.phase()).isNull()
        probe.updateRainmaker(listOf("Phase 1: Preparation next in 5:00"), emptyList(), listOf("WHITE"))
        assertThatThrownBy { probe.phase() }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("phase signal changed")
        // a host that reports no colour at all for a present bar is the same failure, not a text fallback
        probe.updateRainmaker(listOf("Phase 1: Preparation next in 5:00"), emptyList(), emptyList())
        assertThatThrownBy { probe.phase() }.isInstanceOf(IllegalStateException::class.java)
    }
}
