package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.gui.binding.CompositeGuiBindingResolver
import com.astarworks.astera.core.application.port.outbound.IReloadableContentsRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.stats.application.service.gui.PlayerAchievementListGuiBindingResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProgressionShutdownScenarioTest {
    @Test
    fun `stopping stats and economy detaches their reload entries and achievement resolver`() {
        scenario("progression-shutdown") {
            val reloads = sim.services.getOrThrow<IReloadableContentsRegistry>()
            val bindings = sim.services.getOrThrow<CompositeGuiBindingResolver>()
            assertThat(reloads.categories()).contains("normas", "achievements", "skills-progression", "bet-policies")
            assertThat(bindings.delegateSnapshot().filterIsInstance<PlayerAchievementListGuiBindingResolver>()).hasSize(1)

            sim.stats.shutdown()
            assertThat(reloads.categories()).doesNotContain("normas", "achievements", "skills-progression")
            assertThat(reloads.categories()).contains("bet-policies", "stages")
            assertThat(bindings.delegateSnapshot().filterIsInstance<PlayerAchievementListGuiBindingResolver>()).isEmpty()

            sim.economy.shutdown()
            assertThat(reloads.categories()).doesNotContain("bet-policies")
            assertThat(reloads.categories()).contains("stages")
        }
    }
}
