package com.astarworks.astera.match.domain.gamemode

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.mode.GameModeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ADR-0106 Wave 5gamemode — [GameModeContext] immutability + typed read tests.
 *
 * Distinct from the legacy [com.astarworks.astera.match.domain.mode.GameModeContextTest]
 * which covers the Wave 5c config-side context (sealed `GameMode` carrier +
 * flat `Map<String, String>`). Both contexts coexist per the IGameMode kdoc.
 */
class GameModeContextTest {

    private val matchId = MatchId("test-match-1")
    private val modeId = GameModeId("norma")
    private val p1 = PlayerId(UUID.randomUUID())
    private val p2 = PlayerId(UUID.randomUUID())

    @Test
    fun `withState returns a copy with the new key`() {
        val ctx = GameModeContext(matchId = matchId, modeId = modeId)
        val next = ctx.withState("score", 42)
        assertThat(ctx.scoringState).isEmpty()
        assertThat(next.scoringState).containsEntry("score", 42)
    }

    @Test
    fun `withParticipants returns a copy with the replaced participant list`() {
        val ctx = GameModeContext(matchId = matchId, modeId = modeId, participants = listOf(p1))
        val next = ctx.withParticipants(listOf(p1, p2))
        assertThat(ctx.participants).containsExactly(p1)
        assertThat(next.participants).containsExactly(p1, p2)
    }

    @Test
    fun `readState returns typed value when runtime type matches`() {
        val ctx = GameModeContext(matchId = matchId, modeId = modeId)
            .withState("count", 7)
            .withState("label", "alpha")
        assertThat(ctx.readState<Int>("count")).isEqualTo(7)
        assertThat(ctx.readState<String>("label")).isEqualTo("alpha")
    }

    @Test
    fun `readState returns null on type mismatch or missing key`() {
        val ctx = GameModeContext(matchId = matchId, modeId = modeId).withState("count", 7)
        assertThat(ctx.readState<String>("count")).isNull()
        assertThat(ctx.readState<Int>("missing")).isNull()
    }
}
