package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IEgoGauge
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.items.application.service.glow.InMemoryGlowProfileRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** ECO reload gap: the core balance files and the glow profile reload live; a bad file keeps the old values. */
class BalanceReloadScenarioTest {

    @Test
    fun `a raised starter grant tops up existing profiles, a lowered one claws nothing back, ego config swaps`() {
        val content = ReloadScenarioSupport.scratchContent("balance")
        val grantFile = content.resolve("balance/starter-grant.yaml")
        ReloadScenarioSupport.withSim("balance-reload", content) { sim ->
            val ledger = sim.services.getOrThrow<ICurrencyLedger>()
            val ego = sim.services.getOrThrow<IEgoGauge>()
            val player = sim.spawnPlayer("veteran")
            sim.step(2)
            val start = ledger.balance(player.id, Currency.GOLD)

            Files.writeString(grantFile, Files.readString(grantFile).replace("GOLD: 150", "GOLD: 400"))
            val report = (ReloadScenarioSupport.reload(sim, "balance-core") as Result.Success).value
            assertThat(report.notes.joinToString("\n")).contains("topped up")
            assertThat(ledger.balance(player.id, Currency.GOLD)).isEqualTo(start + 250L)

            Files.writeString(grantFile, Files.readString(grantFile).replace("GOLD: 400", "GOLD: 100"))
            ReloadScenarioSupport.reload(sim, "balance-core")
            assertThat(ledger.balance(player.id, Currency.GOLD)).`as`("no claw-back").isEqualTo(start + 250L)

            val egoFile = content.resolve("balance/ego.yaml")
            val egoText = Files.readString(egoFile)
            val before = ego.config
            Files.writeString(egoFile, egoText) // unchanged content must reload cleanly
            assertThat(ReloadScenarioSupport.reload(sim, "balance-core")).isInstanceOf(Result.Success::class.java)
            assertThat(ego.config).isEqualTo(before)
        }
    }

    @Test
    fun `a broken balance file keeps every old value and reports an error`() {
        val content = ReloadScenarioSupport.scratchContent("balance")
        ReloadScenarioSupport.withSim("balance-reload-bad", content) { sim ->
            val ego = sim.services.getOrThrow<IEgoGauge>()
            val before = ego.config
            val grantFile = content.resolve("balance/starter-grant.yaml")
            Files.writeString(grantFile, "wallet_grants: [not, a, map")
            assertThat(ReloadScenarioSupport.reload(sim, "balance-core")).isInstanceOf(Result.Failure::class.java)
            assertThat(ego.config).isEqualTo(before)
        }
    }

    @Test
    fun `glow reload swaps the rarity table and reverts a removed override to its default`() {
        val content = ReloadScenarioSupport.scratchContent("flex")
        val file = content.resolve("flex/glow-profile.yaml")
        ReloadScenarioSupport.withSim("glow-reload", content) { sim ->
            val glow = sim.services.getOrThrow<InMemoryGlowProfileRegistry>()
            val rare = com.astarworks.astera.core.domain.item.Rarity.RARE
            val shipped = glow.resolve(rare)
            Files.writeString(
                file,
                "profiles:\n  - rarity: RARE\n    particle_tick_period: 7\n    particle_count: 9\n    glint_override: true\n    visible_from_blocks: 3\n",
            )
            assertThat(ReloadScenarioSupport.reload(sim, "glow")).isInstanceOf(Result.Success::class.java)
            assertThat(glow.resolve(rare).particleCount).isEqualTo(9)

            Files.writeString(file, "profiles: [broken")
            assertThat(ReloadScenarioSupport.reload(sim, "glow")).isInstanceOf(Result.Failure::class.java)
            assertThat(glow.resolve(rare).particleCount).`as`("old table kept").isEqualTo(9)

            Files.writeString(file, "profiles: []\n")
            ReloadScenarioSupport.reload(sim, "glow")
            assertThat(glow.resolve(rare)).isEqualTo(com.astarworks.astera.items.domain.glow.GlowProfile.DEFAULTS.getValue(rare))
            assertThat(shipped).isNotNull
        }
    }
}
