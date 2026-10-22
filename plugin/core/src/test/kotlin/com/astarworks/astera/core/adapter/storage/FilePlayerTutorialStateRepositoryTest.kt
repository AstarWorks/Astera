package com.astarworks.astera.core.adapter.storage

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialRunnerSnapshot
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class FilePlayerTutorialStateRepositoryTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `cursor pause and graduation survive reopening without mixing players`() {
        val alice = PlayerId(UUID.randomUUID())
        val bob = PlayerId(UUID.randomUUID())
        val first = TutorialRunnerSnapshot(TutorialScriptId("getting-started"), 4, 900, paused = true)
        val completed = first.copy(paused = false, completed = true, rewardGranted = true)
        FilePlayerTutorialStateRepository(directory).apply { save(alice, first); save(bob, completed) }
        val reopened = FilePlayerTutorialStateRepository(directory)
        assertThat(reopened.find(alice)).isEqualTo(first)
        assertThat(reopened.find(bob)).isEqualTo(completed)
        reopened.save(alice, first.copy(stepIndex = 5, paused = false))
        assertThat(FilePlayerTutorialStateRepository(directory).find(alice)?.stepIndex).isEqualTo(5)
        assertThat(reopened.all()).hasSize(2)
        reopened.clear(alice)
        assertThat(reopened.find(alice)).isNull()
        assertThat(reopened.find(bob)).isEqualTo(completed)
    }
}
