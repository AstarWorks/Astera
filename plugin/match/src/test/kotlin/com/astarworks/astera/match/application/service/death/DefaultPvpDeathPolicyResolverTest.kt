package com.astarworks.astera.match.application.service.death

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.service.InMemoryMatchModeRegistry
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.domain.death.PvpDeathRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DefaultPvpDeathPolicyResolverTest {

    @Test
    fun `unknown match falls back to KeepInventory`() {
        val r = newResolver()
        assertThat(r.resolveForMatch(MatchId("nope"))).isEqualTo(PvpDeathRule.KeepInventory)
    }

    @Test
    fun `mode null resolves to KeepInventory`() {
        val (r, matches, mode) = newResolverWithDeps()
        val id = MatchId("m1")
        matches.save(matchOf(id, StageId("s1")))
        mode.register(id, null)
        assertThat(r.resolveForMatch(id)).isEqualTo(PvpDeathRule.KeepInventory)
    }

    @Test
    fun `mode annihilation resolves to DropEquipped`() {
        val (r, matches, mode) = newResolverWithDeps()
        val id = MatchId("m1")
        matches.save(matchOf(id, StageId("s1")))
        mode.register(id, "annihilation")
        assertThat(r.resolveForMatch(id)).isEqualTo(PvpDeathRule.DropEquipped)
    }

    @Test
    fun `mode bet-1v1 resolves to DropAll`() {
        val (r, matches, mode) = newResolverWithDeps()
        val id = MatchId("m1")
        matches.save(matchOf(id, StageId("s1")))
        mode.register(id, "bet-1v1")
        assertThat(r.resolveForMatch(id)).isEqualTo(PvpDeathRule.DropAll)
    }

    @Test
    fun `stage override wins over mode default`() {
        val (r, matches, mode) = newResolverWithDeps()
        val id = MatchId("m1")
        val stage = StageId("ranked-fair-arena")
        matches.save(matchOf(id, stage))
        mode.register(id, "annihilation") // would normally → DropEquipped
        r.setStageOverride(stage.value, PvpDeathRule.KeepInventory)
        assertThat(r.resolveForMatch(id)).isEqualTo(PvpDeathRule.KeepInventory)
    }

    @Test
    fun `setStageOverride null removes prior override`() {
        val (r, matches, mode) = newResolverWithDeps()
        val id = MatchId("m1")
        val stage = StageId("temp-stage")
        matches.save(matchOf(id, stage))
        mode.register(id, "annihilation")
        r.setStageOverride(stage.value, PvpDeathRule.DropAll)
        assertThat(r.resolveForMatch(id)).isEqualTo(PvpDeathRule.DropAll)
        r.setStageOverride(stage.value, null)
        assertThat(r.resolveForMatch(id)).isEqualTo(PvpDeathRule.DropEquipped)
    }

    private fun newResolver(): DefaultPvpDeathPolicyResolver = newResolverWithDeps().resolver

    /** Destructurable triple of (resolver, matches, mode) — data class gives free componentN(). */
    public data class Deps(
        val resolver: DefaultPvpDeathPolicyResolver,
        val matches: InMemoryMatchRepository,
        val mode: InMemoryMatchModeRegistry,
    )

    private fun newResolverWithDeps(): Deps {
        val matches = InMemoryMatchRepository()
        val mode = InMemoryMatchModeRegistry()
        val resolver = DefaultPvpDeathPolicyResolver(matches, mode)
        return Deps(resolver, matches, mode)
    }

    private fun matchOf(id: MatchId, stage: StageId): Match = Match(
        id = id,
        phase = MatchPhase.ACTIVE,
        teams = listOf(
            Team(
                id = TeamId("red"),
                displayNameKey = MessageKey("astera.match.team.red"),
                colorKey = MessageKey("astera.match.team.red.color"),
                members = emptySet(),
            ),
        ),
        score = Score(mapOf(TeamId("red") to 0)),
        stageId = stage,
        startedAtTick = 0L,
    )
}

