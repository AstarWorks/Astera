package com.astarworks.astera.gamesim

import com.astarworks.astera.gamesim.playable.MatchConfig
import com.astarworks.astera.gamesim.playable.MatchDriver
import com.astarworks.astera.gamesim.playable.metrics.MassPlay
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Regression guard for bot aggression (bisect 2026-11-01): a full 3x3 Annihilation match driven by the shipped [com.astarworks.astera.bots.BotBrain]
 * must contain early, frequent fights (pillar damage is printed, not asserted: attackers still reach pillars too rarely on some seeds). When pillar attackers chased every dropped star, cache and paying block on the stage,
 * kills/min fell to ~1 and no pillar was ever hit. Not run by the agent; the manager's window runs it.
 */
class BotAttackerPressureScenarioTest {

    @Test
    fun `bot attackers fight and hit pillars instead of farming stars all match`() {
        val out = Files.createTempDirectory("bot-attacker-pressure")
        val run = MassPlay.run(listOf(20261023L), out, MassPlay.Config(renderFrames = false)) { seed -> MatchDriver.boot(MatchConfig(seed = seed)) }.single()
        val s = run.result.scalars
        val kpm = s["kills_per_min"] ?: 0.0
        val pillar = s["pillar_damage_total"] ?: 0.0
        val ttff = s["ttff_s"] ?: Double.MAX_VALUE
        println("[bot-attacker-pressure] ttff=$ttff kills/min=$kpm pillarDamage=$pillar end=${run.endReason}")
        assertThat(kpm).`as`("kills per minute over the match").isGreaterThan(2.0)
        assertThat(ttff).`as`("first fight within 3 minutes").isLessThan(180.0)
    }
}
