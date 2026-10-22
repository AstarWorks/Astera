package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.gamemode.GameModeContext
import com.astarworks.astera.match.domain.gamemode.GameModeEvent
import com.astarworks.astera.match.domain.gamemode.IGameModePhase
import com.astarworks.astera.match.domain.mode.GameModeId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The remaining thin-façade [com.astarworks.astera.match.domain.gamemode.IGameMode]:
 * `carry-mini-tnt` (event-less, publishes framework events on lifecycle
 * boundaries only). The other five objective modes are exercised end to end by
 * the `:tools:game-sim` siege scenarios.
 */
class Wave5cGameModesFacadeTest {

    private val ctx = GameModeContext(matchId = MatchId("equiv-carry-mini-tnt"), modeId = GameModeId("carry-mini-tnt"))

    @Test
    fun `defaults expose the canonical metadata`() {
        val mode = CarryMiniTntGameMode()
        assertThat(mode.id().value).isEqualTo("carry-mini-tnt")
        assertThat(mode.titleKey().value).isEqualTo("astera.gamemode.carry-mini-tnt.title")
        assertThat(mode.tickPeriodTicks()).isEqualTo(20L)
    }

    @Test
    fun `lifecycle publishes one framework event per boundary and nothing per tick`() {
        val bus = FakeDomainEventBus()
        val mode = CarryMiniTntGameMode(eventBus = bus)

        mode.onMatchStarted(ctx)
        mode.onTick(ctx, tick = 100L)
        mode.onPhaseChanged(ctx, IGameModePhase.Lobby, IGameModePhase.Active)
        mode.onPhaseChanged(ctx, IGameModePhase.Active, IGameModePhase.Active)
        mode.onMatchEnded(ctx)

        assertThat(bus.publishedOfType<GameModeEvent.GameModeStarted>()).hasSize(1)
        assertThat(bus.publishedOfType<GameModeEvent.GameModePhaseChanged>()).hasSize(1)
        assertThat(bus.publishedOfType<GameModeEvent.GameModeEnded>()).hasSize(1)
    }

    @Test
    fun `null event bus makes every hook a clean no-op`() {
        val mode = CarryMiniTntGameMode()
        mode.onMatchStarted(ctx)
        mode.onPhaseChanged(ctx, IGameModePhase.Lobby, IGameModePhase.Active)
        mode.onTick(ctx, tick = 1L)
        mode.onMatchEnded(ctx)
    }
}
