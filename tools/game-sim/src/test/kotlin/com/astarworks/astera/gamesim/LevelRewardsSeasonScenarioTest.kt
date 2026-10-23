package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IPlayerUnlockView
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.proxy.ServerRole
import com.astarworks.astera.stats.application.port.outbound.IPlayerXpRepository
import com.astarworks.astera.stats.application.port.outbound.ISeasonRepository
import com.astarworks.astera.stats.application.usecase.season.CheckSeasonEndUseCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class LevelRewardsSeasonScenarioTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `gain XP across two levels then relog without granting either reward twice`() {
        scenario("level-rewards-two-levels") {
            val alice = player("alice")
            val ledger = sim.services.getOrThrow<ICurrencyLedger>()
            sim.stats.grantXp.execute(alice.id, 300L, XpSource.AdminGrant)

            val payouts = eventsOf<CurrencyChanged>().filter { it.reason is CurrencyChangeReason.LevelReward }
            assertThat(payouts.map { (it.reason as CurrencyChangeReason.LevelReward).level }).containsExactly(2, 3)
            assertThat(payouts.sumOf { it.delta }).isEqualTo(125L)
            assertThat(sim.services.getOrThrow<IPlayerUnlockView>().unlocks(alice.id))
                .containsExactlyInAnyOrder("title:newcomer", "cosmetic:rising-star")
            assertThat(sim.server.view(alice.id)!!.messages.joinToString("\n"))
                .contains("Level 2 rewards:", "Level 3 rewards:", "+50 GOLD", "+75 GOLD")
            val view = sim.server.view(alice.id)!!
            sim.services.getOrThrow<IGuiIntentRouter>().dispatch(view, GuiIntent("lobby.profile.show"))
            val card = sim.services.getOrThrow<IGuiAdapter>().currentSpec(view)!!
            val unlockIds = card.slots.values.filterIsInstance<GuiSlot.Static>().flatMap { it.lore }
                .filter { it.key.value == "astera.progression.unlocks.entry" }.map { it.args["id"] }
            assertThat(unlockIds).containsExactlyInAnyOrder("title:newcomer", "cosmetic:rising-star")
            val settled = ledger.balance(alice.id, Currency.GOLD)
            sim.detachPlayer(alice.id)
            sim.attachPlayer(alice.id, "alice")
            assertThat(ledger.balance(alice.id, Currency.GOLD)).isEqualTo(settled)
            assertThat(eventsOf<CurrencyChanged>().count { it.reason is CurrencyChangeReason.LevelReward }).isEqualTo(2)
        }
    }

    @Test
    fun `login recovers rewards when XP was stored before reward reconciliation`() {
        scenario("level-rewards-login-recovery") {
            val alice = player("alice")
            sim.services.getOrThrow<IPlayerXpRepository>().addXp(alice.id, 300L)
            sim.detachPlayer(alice.id)
            sim.attachPlayer(alice.id, "alice")
            assertThat(sim.services.getOrThrow<IPlayerUnlockView>().unlocks(alice.id))
                .containsExactlyInAnyOrder("title:newcomer", "cosmetic:rising-star")
            assertThat(eventsOf<CurrencyChanged>().filter { it.reason is CurrencyChangeReason.LevelReward }.sumOf { it.delta })
                .isEqualTo(125L)
        }
    }

    /** Keep the real catalog, overriding just the season clock window with one already ended. */
    private fun endedSeasonContent(): Path {
        val source = GameSim.defaultContentDir()
        Files.list(source).use { paths ->
            paths.filter { it.fileName.toString() != "progression" }.forEach {
                Files.createSymbolicLink(temp.resolve(it.fileName), it)
            }
        }
        val progression = Files.createDirectory(temp.resolve("progression"))
        Files.copy(source.resolve("progression/level-rewards.yaml"), progression.resolve("level-rewards.yaml"))
        Files.writeString(progression.resolve("seasons.yaml"), """
            seasons:
              - id: ended-preseason
                start: "2020-01-01T00:00:00Z"
                end: "2020-02-01T00:00:00Z"
                next: "Scheduled season"
        """.trimIndent())
        return temp
    }

    @Test
    fun `scheduled lobby check resets once and game role never schedules a reset`() {
        val content = endedSeasonContent()
        val lobby = GameSim.boot("season-lobby-schedule", contentDir = content, role = ServerRole.LOBBY)
        val game = GameSim.boot("season-game-no-schedule", contentDir = content, role = ServerRole.GAME)
        try {
            val alice = lobby.spawnPlayer("alice")
            val seasons = lobby.services.getOrThrow<ISeasonRepository>()
            val before = seasons.all().size
            lobby.step(2)
            assertThat(seasons.all()).hasSize(before + 1)
            assertThat(seasons.current()!!.label).isEqualTo("Scheduled season")
            assertThat(lobby.server.view(alice.id)!!.messages.joinToString("\n"))
                .contains("Season ended-preseason ended.")
            lobby.step(2400)
            assertThat(seasons.all()).hasSize(before + 1)
            // A reconstructed use case shares the repository's durable schedule marker.
            val checker = CheckSeasonEndUseCase(
                listOf(com.astarworks.astera.stats.application.config.SeasonWindowYamlConfig(
                    "ended-preseason", "2020-01-01T00:00:00Z", "2020-02-01T00:00:00Z", "Scheduled season",
                )),
                com.astarworks.astera.stats.application.usecase.season.ResetSeasonUseCase(seasons),
            ) { _, _ -> error("Must not announce twice") }
            assertThat(checker.execute(Instant.now()).getOrNull()).isZero()
            assertThat(lobby.server.view(alice.id)!!.messages.count { it.contains("Season ended-preseason ended.") }).isEqualTo(1)
            assertThat(game.services.get<CheckSeasonEndUseCase>()).isNull()
            val gameSeasons = game.services.getOrThrow<ISeasonRepository>()
            val gameBefore = gameSeasons.all().size
            game.step(2402)
            assertThat(gameSeasons.all()).hasSize(gameBefore)
        } finally {
            lobby.shutdown()
            game.shutdown()
        }
    }
}
