package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialRunnerSnapshot
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class PostgresPlayerTutorialStateRepositoryIT : PostgresIntegrationTestBase() {
    @Test
    fun `cursor pause and reward latch round trip across repository instances`() {
        kotlinx.coroutines.runBlocking {
        val id = PlayerId(UUID.randomUUID())
        val state = TutorialRunnerSnapshot(TutorialScriptId("getting-started"), 5, 500, paused = true)
        PostgresPlayerTutorialStateRepository(db).save(id, state)
        val repo = PostgresPlayerTutorialStateRepository(db)
        assertThat(repo.find(id)).isEqualTo(state)
        val complete = state.copy(completed = true, paused = false, rewardGranted = true)
        repo.save(id, complete)
        assertThat(PostgresPlayerTutorialStateRepository(db).find(id)).isEqualTo(complete)
        assertThat(repo.all()).containsEntry(id, complete)
        repo.clear(id)
        assertThat(repo.find(id)).isNull()
        }
}
}
