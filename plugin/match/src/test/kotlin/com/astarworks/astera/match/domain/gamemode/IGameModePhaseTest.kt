package com.astarworks.astera.match.domain.gamemode

import com.astarworks.astera.core.domain.match.MatchPhase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0106 Wave 5gamemode — [IGameModePhase] fromCore/toCore round-trip
 * tests. Pinned so any future addition of a custom phase keeps the canonical
 * five-variant mapping intact.
 */
class IGameModePhaseTest {

    @Test
    fun `fromCore maps every MatchPhase to a sealed IGameModePhase`() {
        assertThat(IGameModePhase.fromCore(MatchPhase.WAITING)).isEqualTo(IGameModePhase.Lobby)
        assertThat(IGameModePhase.fromCore(MatchPhase.COUNTDOWN)).isEqualTo(IGameModePhase.Countdown)
        assertThat(IGameModePhase.fromCore(MatchPhase.ACTIVE)).isEqualTo(IGameModePhase.Active)
        assertThat(IGameModePhase.fromCore(MatchPhase.SUDDEN_DEATH)).isEqualTo(IGameModePhase.SuddenDeath)
        assertThat(IGameModePhase.fromCore(MatchPhase.ENDED)).isEqualTo(IGameModePhase.Ended)
    }

    @Test
    fun `toCore round-trips the canonical five variants`() {
        for (core in MatchPhase.entries) {
            val framework = IGameModePhase.fromCore(core)
            assertThat(IGameModePhase.toCore(framework)).isEqualTo(core)
        }
    }
}
