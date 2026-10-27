package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.SprintJumpPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * NPC (bot) combat state. The decisions are the live [SprintJumpPolicy] that BotBrain calls:
 * - a bot is in combat only when an enemy is within engage range, retreating or reloading under fire; an enemy that
 *   is merely visible far away is a trip to make, not a fight;
 */
class BotCombatStateScenarioTest {

    /** Vanilla player sprint-jump: about 7.1 blocks/s. */
    private val playerDashJump = 0.356

    @Test
    fun `an enemy far away is a trip, not combat`() {
        val p = SprintJumpPolicy
        assertThat(p.inCombat(enemyFlat = null, retreating = false, reloading = false)).isFalse()
        assertThat(p.inCombat(enemyFlat = 40.0, retreating = false, reloading = false)).`as`("enemy 40 blocks away").isFalse()
        assertThat(p.inCombat(enemyFlat = 5.0, retreating = false, reloading = false)).`as`("enemy 5 blocks away").isTrue()
        assertThat(p.inCombat(enemyFlat = 40.0, retreating = true, reloading = false)).`as`("fleeing").isTrue()
        assertThat(p.inCombat(enemyFlat = 40.0, retreating = false, reloading = true)).`as`("reloading with an enemy in sight").isTrue()
        assertThat(p.inCombat(enemyFlat = null, retreating = false, reloading = true)).`as`("reloading, nobody around").isFalse()
    }
}
