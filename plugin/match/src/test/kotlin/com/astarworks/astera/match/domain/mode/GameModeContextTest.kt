package com.astarworks.astera.match.domain.mode

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GameModeContextTest {

    private val matchId = MatchId("match-ctx-1")
    private val mode = StealJewelry(
        id = GameModeId("steal-jewelry"),
        displayNameKey = MessageKey("astera.mode.steal-jewelry.name"),
        descriptionKey = MessageKey("astera.mode.steal-jewelry.desc"),
        depositRegionIds = mapOf(TeamId("red") to "gem_deposit_red"),
    )

    @Test
    fun `default state map is empty`() {
        val ctx = GameModeContext(matchId, mode)
        assertThat(ctx.modeState).isEmpty()
    }

    @Test
    fun `withState adds a new key without mutating the original`() {
        val ctx = GameModeContext(matchId, mode)
        val updated = ctx.withState("phase", "stealing")

        assertThat(ctx.modeState).isEmpty()
        assertThat(updated.modeState).containsExactlyEntriesOf(mapOf("phase" to "stealing"))
        assertThat(updated.matchId).isEqualTo(matchId)
        assertThat(updated.mode).isSameAs(mode)
    }

    @Test
    fun `withState overwrites an existing key`() {
        val ctx = GameModeContext(matchId, mode, mapOf("phase" to "stealing"))
        val updated = ctx.withState("phase", "delivering")

        assertThat(updated.modeState).containsExactlyEntriesOf(mapOf("phase" to "delivering"))
    }

    @Test
    fun `withState preserves unrelated keys`() {
        val ctx = GameModeContext(matchId, mode, mapOf("a" to "1", "b" to "2"))
        val updated = ctx.withState("c", "3")

        assertThat(updated.modeState).containsExactlyInAnyOrderEntriesOf(
            mapOf("a" to "1", "b" to "2", "c" to "3"),
        )
    }
}
