package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class PostgresContentStageMatchIT : PostgresIntegrationTestBase() {
    @Test
    fun `a match on a content stage persists without a duplicate stage database row`() {
        val id = UUID.randomUUID().toString().take(8)
        val match = Match(
            id = MatchId("content-$id"),
            phase = MatchPhase.ACTIVE,
            teams = listOf(Team(TeamId("red"), MessageKey("red"), MessageKey("red"), emptySet())),
            score = Score(perTeam = emptyMap()),
            stageId = StageId("yaml-$id"),
            startedAtTick = 0L,
        )
        val repo = PostgresMatchRepository(db)
        repo.save(match)
        assertThat(PostgresMatchRepository(db).findById(match.id)).isEqualTo(match)
    }
}
