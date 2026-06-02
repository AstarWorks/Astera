package com.astarworks.astera.match.application.gamemode

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.gamemode.GameModeContext
import com.astarworks.astera.match.domain.gamemode.GameModeEvent
import com.astarworks.astera.match.domain.gamemode.IGameMode
import com.astarworks.astera.match.domain.gamemode.IGameModePhase
import com.astarworks.astera.match.domain.mode.GameModeId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * ADR-0165 Wave 5c-N1 — parameterized equivalence test for the six
 * thin-façade [IGameMode] impls added in this wave (BattleLoyal / BreakCore /
 * CarryTnt / CarryMiniTnt / CaptureWool / StealJewelry).
 *
 * Each façade is structurally identical to [NormaGameMode] (publishes
 * framework events on lifecycle boundaries, no-op `onTick`) so a single
 * parameterized harness covers all six instead of six near-identical files.
 *
 * The contract verified mirrors [NormaGameModeEquivalenceTest]:
 *
 *   1. [IGameMode.id] / [IGameMode.titleKey] return the values declared in
 *      the impl's companion (so the registry key + i18n routing are stable).
 *   2. [IGameMode.defaultPhases] is the canonical five-phase list.
 *   3. [IGameMode.tickPeriodTicks] is 20L (1s cadence).
 *   4. [IGameMode.onTick] is a side-effect-free no-op — the legacy listeners
 *      (`BattleLoyalListener`, `BreakCoreListener`, …) drive scoring/win
 *      conditions globally, so the façade publishes nothing per tick.
 *   5. [IGameMode.onMatchStarted] / [IGameMode.onMatchEnded] publish exactly
 *      one framework event each (with correct match + mode ids).
 *   6. [IGameMode.onPhaseChanged] publishes one event per real transition and
 *      0 when `oldPhase == newPhase`.
 *   7. With a null event bus every hook is a clean no-op (no NPE).
 */
class Wave5cGameModesFacadeTest {

    data class FacadeCase(
        val label: String,
        val expectedId: String,
        val expectedTitleKey: String,
        val factory: (FakeDomainEventBus?) -> IGameMode,
    ) {
        override fun toString(): String = label
    }

    companion object {
        @JvmStatic
        fun cases(): Stream<Arguments> = Stream.of(
            Arguments.of(
                FacadeCase(
                    label = "BattleLoyal",
                    expectedId = "battle-loyal",
                    expectedTitleKey = "astera.gamemode.battle-loyal.title",
                    factory = { bus -> BattleLoyalGameMode(eventBus = bus) },
                ),
            ),
            Arguments.of(
                FacadeCase(
                    label = "BreakCore",
                    expectedId = "break-core",
                    expectedTitleKey = "astera.gamemode.break-core.title",
                    factory = { bus -> BreakCoreGameMode(eventBus = bus) },
                ),
            ),
            Arguments.of(
                FacadeCase(
                    label = "CarryTnt",
                    expectedId = "carry-tnt",
                    expectedTitleKey = "astera.gamemode.carry-tnt.title",
                    factory = { bus -> CarryTntGameMode(eventBus = bus) },
                ),
            ),
            Arguments.of(
                FacadeCase(
                    label = "CarryMiniTnt",
                    expectedId = "carry-mini-tnt",
                    expectedTitleKey = "astera.gamemode.carry-mini-tnt.title",
                    factory = { bus -> CarryMiniTntGameMode(eventBus = bus) },
                ),
            ),
            Arguments.of(
                FacadeCase(
                    label = "CaptureWool",
                    expectedId = "capture-wool",
                    expectedTitleKey = "astera.gamemode.capture-wool.title",
                    factory = { bus -> CaptureWoolGameMode(eventBus = bus) },
                ),
            ),
            Arguments.of(
                FacadeCase(
                    label = "StealJewelry",
                    expectedId = "steal-jewelry",
                    expectedTitleKey = "astera.gamemode.steal-jewelry.title",
                    factory = { bus -> StealJewelryGameMode(eventBus = bus) },
                ),
            ),
        )
    }

    private fun contextFor(case: FacadeCase): GameModeContext =
        GameModeContext(matchId = MatchId("equiv-${case.expectedId}"), modeId = GameModeId(case.expectedId))

    @ParameterizedTest(name = "{0}: defaults expose the canonical metadata")
    @MethodSource("cases")
    fun `defaults expose the canonical metadata`(case: FacadeCase) {
        val mode = case.factory(null)
        assertThat(mode.id().value).isEqualTo(case.expectedId)
        assertThat(mode.titleKey().value).isEqualTo(case.expectedTitleKey)
        assertThat(mode.tickPeriodTicks()).isEqualTo(20L)
        assertThat(mode.defaultPhases()).containsExactly(
            IGameModePhase.Lobby,
            IGameModePhase.Countdown,
            IGameModePhase.Active,
            IGameModePhase.SuddenDeath,
            IGameModePhase.Ended,
        )
    }

    @ParameterizedTest(name = "{0}: onTick is a side-effect-free no-op")
    @MethodSource("cases")
    fun `onTick is a side-effect-free no-op`(case: FacadeCase) {
        val bus = FakeDomainEventBus()
        val mode = case.factory(bus)
        val ctx = contextFor(case)

        mode.onTick(ctx, tick = 100L)
        mode.onTick(ctx, tick = 200L)
        mode.onTick(ctx, tick = 300L)

        assertThat(bus.published).isEmpty()
    }

    @ParameterizedTest(name = "{0}: onMatchStarted publishes exactly one GameModeStarted")
    @MethodSource("cases")
    fun `onMatchStarted publishes exactly one GameModeStarted`(case: FacadeCase) {
        val bus = FakeDomainEventBus()
        val mode = case.factory(bus)
        val ctx = contextFor(case)

        mode.onMatchStarted(ctx)

        val events = bus.publishedOfType<GameModeEvent.GameModeStarted>()
        assertThat(events).hasSize(1)
        assertThat(events[0].matchId).isEqualTo(ctx.matchId)
        assertThat(events[0].modeId.value).isEqualTo(case.expectedId)
    }

    @ParameterizedTest(name = "{0}: onMatchEnded publishes exactly one GameModeEnded")
    @MethodSource("cases")
    fun `onMatchEnded publishes exactly one GameModeEnded`(case: FacadeCase) {
        val bus = FakeDomainEventBus()
        val mode = case.factory(bus)
        val ctx = contextFor(case)

        mode.onMatchEnded(ctx)

        val events = bus.publishedOfType<GameModeEvent.GameModeEnded>()
        assertThat(events).hasSize(1)
        assertThat(events[0].matchId).isEqualTo(ctx.matchId)
        assertThat(events[0].modeId.value).isEqualTo(case.expectedId)
    }

    @ParameterizedTest(name = "{0}: onPhaseChanged publishes one event per real transition")
    @MethodSource("cases")
    fun `onPhaseChanged publishes one event per real transition`(case: FacadeCase) {
        val bus = FakeDomainEventBus()
        val mode = case.factory(bus)
        val ctx = contextFor(case)

        mode.onPhaseChanged(ctx, IGameModePhase.Lobby, IGameModePhase.Countdown)
        mode.onPhaseChanged(ctx, IGameModePhase.Countdown, IGameModePhase.Active)
        mode.onPhaseChanged(ctx, IGameModePhase.Active, IGameModePhase.SuddenDeath)
        mode.onPhaseChanged(ctx, IGameModePhase.SuddenDeath, IGameModePhase.Ended)

        val events = bus.publishedOfType<GameModeEvent.GameModePhaseChanged>()
        assertThat(events).hasSize(4)
        assertThat(events.map { it.oldPhase to it.newPhase }).containsExactly(
            IGameModePhase.Lobby to IGameModePhase.Countdown,
            IGameModePhase.Countdown to IGameModePhase.Active,
            IGameModePhase.Active to IGameModePhase.SuddenDeath,
            IGameModePhase.SuddenDeath to IGameModePhase.Ended,
        )
    }

    @ParameterizedTest(name = "{0}: onPhaseChanged dedupes identical-phase calls")
    @MethodSource("cases")
    fun `onPhaseChanged dedupes identical-phase calls`(case: FacadeCase) {
        val bus = FakeDomainEventBus()
        val mode = case.factory(bus)
        val ctx = contextFor(case)

        mode.onPhaseChanged(ctx, IGameModePhase.Active, IGameModePhase.Active)

        assertThat(bus.publishedOfType<GameModeEvent.GameModePhaseChanged>()).isEmpty()
    }

    @ParameterizedTest(name = "{0}: null event bus makes every hook a clean no-op")
    @MethodSource("cases")
    fun `null event bus makes every hook a clean no-op`(case: FacadeCase) {
        val mode = case.factory(null)
        val ctx = contextFor(case)

        mode.onMatchStarted(ctx)
        mode.onPhaseChanged(ctx, IGameModePhase.Lobby, IGameModePhase.Active)
        mode.onTick(ctx, tick = 1L)
        mode.onMatchEnded(ctx)
        // Reaching here without exception is the assertion.
    }

    @Test
    fun `all six modes register six distinct ids`() {
        val ids = cases().map { (it.get()[0] as FacadeCase).expectedId }.toList()
        assertThat(ids).hasSize(6).doesNotHaveDuplicates()
    }
}
