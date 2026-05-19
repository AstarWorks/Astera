package com.astarworks.astera.core.application.usecase.tutorial

import com.astarworks.astera.core.application.contents.TutorialContents
import com.astarworks.astera.core.application.eventbus.listeners.StartTutorialOnFirstJoinListener
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.TutorialCompleted
import com.astarworks.astera.core.domain.event.TutorialSkipped
import com.astarworks.astera.core.domain.event.TutorialStarted
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.PlayerTutorialState
import com.astarworks.astera.core.domain.tutorial.TutorialScript
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.core.domain.tutorial.TutorialStep
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakePlayerTutorialStateRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5h tutorial flow integration tests — exercises the
 * persisted-state slice (`PlayerTutorialState` + `IPlayerTutorialStateRepository`)
 * end-to-end through the four new use cases / the first-join listener.
 *
 * Pairs with [RunTutorialUseCaseTest] which covers the in-memory step runner.
 * Here the focus is the durable lifecycle: first-join auto-trigger, explicit
 * skip / replay, and the final-step completion handover.
 */
class TutorialFlowTest {

    private val defaultId = TutorialScriptId("getting-started")
    private val playerId = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

    private val script = TutorialScript(
        id = defaultId,
        displayKey = MessageKey("astera.tutorial.getting_started.title"),
        steps = listOf(
            TutorialStep.SendMessage(MessageKey("astera.tutorial.welcome")),
            TutorialStep.GiveItem(itemId = "astera:example-pistol", count = 1),
            TutorialStep.SendMessage(MessageKey("astera.tutorial.complete")),
        ),
    )

    private lateinit var repo: FakePlayerTutorialStateRepository
    private lateinit var registry: TutorialContents
    private lateinit var events: FakeDomainEventBus
    private lateinit var listener: StartTutorialOnFirstJoinListener
    private lateinit var skip: SkipTutorialUseCase
    private lateinit var replay: ReplayTutorialUseCase
    private lateinit var advance: AdvanceTutorialStepUseCase

    @BeforeEach
    fun setUp() {
        repo = FakePlayerTutorialStateRepository()
        registry = TutorialContents().also { it.register(script) }
        events = FakeDomainEventBus()
        listener = StartTutorialOnFirstJoinListener(
            repo = repo,
            events = events,
            defaultTutorialId = defaultId,
        )
        skip = SkipTutorialUseCase(repo, events)
        replay = ReplayTutorialUseCase(repo, events)
        advance = AdvanceTutorialStepUseCase(repo, registry, events)
    }

    @Test
    fun `PlayerJoined with empty state triggers default tutorial`() {
        listener.handle(PlayerJoinedServer(playerId))

        val state = repo.find(playerId)
        assertThat(state).isNotNull
        assertThat(state!!.currentTutorial).isEqualTo(defaultId)
        assertThat(state.currentStepIndex).isEqualTo(0)

        val started = events.publishedOfType<TutorialStarted>()
        assertThat(started).hasSize(1)
        assertThat(started.single().playerId).isEqualTo(playerId)
        assertThat(started.single().tutorialId).isEqualTo(defaultId)
    }

    @Test
    fun `PlayerJoined with default in skipped does not trigger listener`() {
        // Player previously skipped the default — opt-out must be respected.
        repo.upsert(
            PlayerTutorialState(
                playerId = playerId,
                skippedTutorialScriptIds = setOf(defaultId),
            ),
        )

        listener.handle(PlayerJoinedServer(playerId))

        val state = repo.find(playerId)!!
        // Cursor stays cleared, completed/skipped sets unchanged.
        assertThat(state.currentTutorial).isNull()
        assertThat(state.currentStepIndex).isNull()
        assertThat(state.skippedTutorialScriptIds).containsExactly(defaultId)
        // No TutorialStarted event published.
        assertThat(events.publishedOfType<TutorialStarted>()).isEmpty()
    }

    @Test
    fun `SkipTutorialUseCase skipping current tutorial clears the cursor`() {
        // Bootstrap a player mid-tutorial.
        repo.upsert(
            PlayerTutorialState(
                playerId = playerId,
                currentTutorial = defaultId,
                currentStepIndex = 1,
            ),
        )

        skip.skip(playerId, defaultId)

        val state = repo.find(playerId)!!
        assertThat(state.skippedTutorialScriptIds).containsExactly(defaultId)
        assertThat(state.currentTutorial).isNull()
        assertThat(state.currentStepIndex).isNull()
        // Lifecycle event published.
        val skipped = events.publishedOfType<TutorialSkipped>()
        assertThat(skipped).hasSize(1)
        assertThat(skipped.single().tutorialId).isEqualTo(defaultId)
    }

    @Test
    fun `ReplayTutorialUseCase resets a completed tutorial to step 0`() {
        // Player completed the default earlier.
        repo.upsert(
            PlayerTutorialState(
                playerId = playerId,
                completedTutorialScriptIds = setOf(defaultId),
            ),
        )

        replay.replay(playerId, defaultId)

        val state = repo.find(playerId)!!
        // Replay clears prior terminal status…
        assertThat(state.completedTutorialScriptIds).doesNotContain(defaultId)
        assertThat(state.skippedTutorialScriptIds).doesNotContain(defaultId)
        // …and resets the cursor to step 0.
        assertThat(state.currentTutorial).isEqualTo(defaultId)
        assertThat(state.currentStepIndex).isEqualTo(0)
        // TutorialStarted lifecycle event published.
        val started = events.publishedOfType<TutorialStarted>()
        assertThat(started).hasSize(1)
        assertThat(started.single().tutorialId).isEqualTo(defaultId)
    }

    @Test
    fun `AdvanceTutorialStepUseCase final step moves id into completed`() {
        // Player is on the script's final step (index 2 of 3).
        repo.upsert(
            PlayerTutorialState(
                playerId = playerId,
                currentTutorial = defaultId,
                currentStepIndex = script.steps.lastIndex,
            ),
        )

        val result = advance.advance(playerId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        // `true` signals the tutorial completed.
        assertThat((result as Result.Success).value).isTrue
        val state = repo.find(playerId)!!
        assertThat(state.completedTutorialScriptIds).containsExactly(defaultId)
        assertThat(state.currentTutorial).isNull()
        assertThat(state.currentStepIndex).isNull()
        // TutorialCompleted event published.
        val completed = events.publishedOfType<TutorialCompleted>()
        assertThat(completed).hasSize(1)
        assertThat(completed.single().tutorialId).isEqualTo(defaultId)
    }
}
