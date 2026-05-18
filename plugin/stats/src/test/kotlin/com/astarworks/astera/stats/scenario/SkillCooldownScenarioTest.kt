package com.astarworks.astera.stats.scenario

import com.astarworks.astera.core.domain.cooldown.CooldownKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wave _infra-1 canonical scenario #3.
 *
 * A skill cooldown started at tick 0 with duration N must report
 * `isCoolingDown=true` immediately and continue blocking re-fire for N
 * ticks. After `advanceTicks(N+1)` the tracker reports the slot as free
 * again. Exercises [com.astarworks.astera.core.application.service.InMemoryCooldownTracker]
 * against the deterministic [com.astarworks.astera.testfixtures.FakeScheduler].
 */
class SkillCooldownScenarioTest {

    @Test
    fun `skill cooldown blocks re-fire within window and clears after`() {
        scenario {
            val caster = player("alice")
            val key = CooldownKey.Skill("fireball")

            harness.cooldownTracker.start(caster.id, key, durationTicks = 20L)
            assertThat(harness.cooldownTracker.isCoolingDown(caster.id, key)).isTrue()

            advanceTicks(10)
            assertThat(harness.cooldownTracker.isCoolingDown(caster.id, key))
                .withFailMessage { "skill still on cooldown after 10 of 20 ticks" }
                .isTrue()

            advanceTicks(11)
            assertThat(harness.cooldownTracker.isCoolingDown(caster.id, key))
                .withFailMessage { "skill cooldown should have expired after 21 ticks" }
                .isFalse()
        }
    }
}
