package com.astarworks.astera.core.application.service.combat

import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.domain.combat.CombatContext
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** ADR-0237: the context is `Match` only for match participants; everything else is a non-counting context. */
class CombatContextResolverTest {

    private val red = PlayerId(UUID.randomUUID())
    private val blue = PlayerId(UUID.randomUUID())
    private val lobbyPlayer = PlayerId(UUID.randomUUID())
    private val matchId = MatchId("m-1")

    private val lookup = object : IMatchTeamLookup {
        override fun teamOf(playerId: PlayerId): TeamId? = when (playerId) {
            red -> TeamId("red")
            blue -> TeamId("blue")
            else -> null
        }

        override fun matchOf(playerId: PlayerId): MatchId? = if (teamOf(playerId) != null) matchId else null
    }
    private val resolver = CombatContextResolver { lookup }

    @Test
    fun `two participants fight in a Match context`() {
        assertThat(resolver.betweenPlayers(red, blue)).isEqualTo(CombatContext.Match(matchId))
    }

    @Test
    fun `an unattributed hit on a participant belongs to the match, on a lobby player to the lobby`() {
        assertThat(resolver.betweenPlayers(null, blue)).isEqualTo(CombatContext.Match(matchId))
        assertThat(resolver.betweenPlayers(null, lobbyPlayer)).isEqualTo(CombatContext.Lobby)
    }

    @Test
    fun `a lobby player on either side makes it a Lobby fight`() {
        assertThat(resolver.betweenPlayers(lobbyPlayer, red)).isEqualTo(CombatContext.Lobby)
        assertThat(resolver.betweenPlayers(red, lobbyPlayer)).isEqualTo(CombatContext.Lobby)
        assertThat(resolver.betweenPlayers(lobbyPlayer, lobbyPlayer)).isEqualTo(CombatContext.Lobby)
    }

    @Test
    fun `without a match plugin nothing is a Match`() {
        val none = CombatContextResolver { null }
        assertThat(none.betweenPlayers(red, blue)).isEqualTo(CombatContext.Lobby)
        assertThat(none.againstEntity(red)).isEqualTo(CombatContext.World)
    }

    @Test
    fun `entities count only through a participating attacker`() {
        assertThat(resolver.againstEntity(red)).isEqualTo(CombatContext.Match(matchId))
        assertThat(resolver.againstEntity(lobbyPlayer)).isEqualTo(CombatContext.World)
        assertThat(resolver.againstEntity(null)).isEqualTo(CombatContext.World)
    }

    @Test
    fun `only Match counts for progression`() {
        assertThat(CombatContext.Match().countsForProgression).isTrue()
        assertThat(listOf(CombatContext.Training, CombatContext.Lobby, CombatContext.World).map { it.countsForProgression })
            .containsOnly(false)
    }
}
