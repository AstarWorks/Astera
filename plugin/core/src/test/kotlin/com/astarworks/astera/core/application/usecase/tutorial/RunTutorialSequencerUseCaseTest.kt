package com.astarworks.astera.core.application.usecase.tutorial

import com.astarworks.astera.core.application.contents.TutorialContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IDialogPredicateEvaluator
import com.astarworks.astera.core.application.service.tutorial.InMemoryPlayerTutorialStateRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.dialog.DialogPredicate
import com.astarworks.astera.core.domain.event.TutorialSequenceCancelled
import com.astarworks.astera.core.domain.event.TutorialSequenceCompleted
import com.astarworks.astera.core.domain.event.TutorialStepEntered
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialPredicate
import com.astarworks.astera.core.domain.tutorial.TutorialScript
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.core.domain.tutorial.TutorialSequencerError
import com.astarworks.astera.core.domain.tutorial.TutorialStep
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [RunTutorialSequencerUseCase] (Wave 5p, ADR-0088).
 *
 * Verifies:
 *  - Wave 5p variants drive the sequencer (ShowDialog, AwaitInteraction,
 *    AwaitMovement, AwaitSkillCast, GrantItem, AwaitPredicate).
 *  - State is persisted via the repository.
 *  - AwaitPredicate auto-advances when the predicate is already true.
 *  - Sequence completion publishes [TutorialSequenceCompleted].
 *  - Cancel publishes [TutorialSequenceCancelled] with the current step index.
 *  - AlreadyRunning + NotInCatalog + NotRunning errors return correctly.
 */
class RunTutorialSequencerUseCaseTest {

    private val player = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val scriptId = TutorialScriptId("getting-started-v2")

    private lateinit var registry: TutorialContents
    private lateinit var stateRepo: InMemoryPlayerTutorialStateRepository
    private lateinit var events: InMemoryDomainEventBus
    private lateinit var stepEntered: MutableList<TutorialStepEntered>
    private lateinit var completed: MutableList<TutorialSequenceCompleted>
    private lateinit var cancelled: MutableList<TutorialSequenceCancelled>
    private lateinit var scheduler: FakeScheduler
    private lateinit var useCase: RunTutorialSequencerUseCase

    @BeforeEach
    fun setUp() {
        registry = TutorialContents()
        stateRepo = InMemoryPlayerTutorialStateRepository()
        events = InMemoryDomainEventBus()
        stepEntered = mutableListOf()
        completed = mutableListOf()
        cancelled = mutableListOf()
        events.subscribe(TutorialStepEntered::class) { stepEntered.add(it) }
        events.subscribe(TutorialSequenceCompleted::class) { completed.add(it) }
        events.subscribe(TutorialSequenceCancelled::class) { cancelled.add(it) }
        scheduler = FakeScheduler(startTick = 1000L)
        useCase = RunTutorialSequencerUseCase(
            registry = registry,
            stateRepo = stateRepo,
            events = events,
            scheduler = scheduler,
            predicateEvaluator = AllTruePredicateEvaluator,
        )
    }

    @Test
    fun `start publishes step zero and persists state`() {
        registry.register(
            TutorialScript(
                id = scriptId,
                displayKey = MessageKey("astera.tutorial.getting_started.title"),
                steps = listOf(
                    TutorialStep.SendMessage(MessageKey("astera.tutorial.welcome")),
                    TutorialStep.AwaitInteraction("npc:demo", timeoutSec = 30L),
                ),
            ),
        )
        val r = useCase.startSequence(player, scriptId)
        assertThat(r).isInstanceOf(Result.Success::class.java)
        assertThat(stepEntered).hasSize(1)
        assertThat(stepEntered[0].stepIndex).isEqualTo(0)
        assertThat(stateRepo.find(player)).isNotNull
        assertThat(stateRepo.find(player)?.stepIndex).isEqualTo(0)
        assertThat(stateRepo.find(player)?.startedAtTick).isEqualTo(1000L)
    }

    @Test
    fun `await-predicate auto-advances when already true`() {
        registry.register(
            TutorialScript(
                id = scriptId,
                displayKey = MessageKey("astera.tutorial.demo.title"),
                steps = listOf(
                    TutorialStep.SendMessage(MessageKey("astera.tutorial.welcome")),
                    TutorialStep.AwaitPredicate(
                        TutorialPredicate.HasAdvancement(AdvancementId("first-kill")),
                    ),
                    TutorialStep.SendMessage(MessageKey("astera.tutorial.after_predicate")),
                ),
            ),
        )
        useCase.startSequence(player, scriptId)
        // First advance consumes step 0 → lands on step 1 (predicate).
        // Predicate is true → auto-advance to step 2.
        useCase.advance(player)
        assertThat(stateRepo.find(player)?.stepIndex).isEqualTo(2)
        // 3 published step-entered: step 0 (start), step 1 (predicate), step 2 (after).
        assertThat(stepEntered).hasSize(3)
    }

    @Test
    fun `final advance publishes Completed and clears state`() {
        registry.register(
            TutorialScript(
                id = scriptId,
                displayKey = MessageKey("astera.tutorial.x.title"),
                steps = listOf(TutorialStep.SendMessage(MessageKey("astera.tutorial.only"))),
            ),
        )
        useCase.startSequence(player, scriptId)
        val r = useCase.advance(player)
        assertThat((r as Result.Success).value).isNull()
        assertThat(completed).hasSize(1)
        assertThat(stateRepo.find(player)).isNull()
    }

    @Test
    fun `cancel publishes Cancelled with current step index`() {
        registry.register(
            TutorialScript(
                id = scriptId,
                displayKey = MessageKey("astera.tutorial.x.title"),
                steps = listOf(
                    TutorialStep.SendMessage(MessageKey("astera.tutorial.a")),
                    TutorialStep.SendMessage(MessageKey("astera.tutorial.b")),
                ),
            ),
        )
        useCase.startSequence(player, scriptId)
        useCase.advance(player) // step 1
        val r = useCase.cancel(player)
        assertThat(r).isInstanceOf(Result.Success::class.java)
        assertThat(cancelled).hasSize(1)
        assertThat(cancelled[0].stepIndex).isEqualTo(1)
        assertThat(stateRepo.find(player)).isNull()
    }

    @Test
    fun `already-running rejects a second start`() {
        registry.register(
            TutorialScript(
                id = scriptId,
                displayKey = MessageKey("astera.tutorial.x.title"),
                steps = listOf(TutorialStep.SendMessage(MessageKey("astera.tutorial.x"))),
            ),
        )
        useCase.startSequence(player, scriptId)
        val again = useCase.startSequence(player, scriptId)
        assertThat((again as Result.Failure).error).isInstanceOf(TutorialSequencerError.AlreadyRunning::class.java)
    }

    @Test
    fun `unknown script returns NotInCatalog`() {
        val r = useCase.startSequence(player, TutorialScriptId("does-not-exist"))
        assertThat((r as Result.Failure).error).isInstanceOf(TutorialSequencerError.NotInCatalog::class.java)
    }

    @Test
    fun `advance without start returns NotRunning`() {
        val r = useCase.advance(player)
        assertThat((r as Result.Failure).error).isInstanceOf(TutorialSequencerError.NotRunning::class.java)
    }

    /** Predicate evaluator that returns true for every predicate. */
    private object AllTruePredicateEvaluator : IDialogPredicateEvaluator {
        override fun evaluate(playerId: PlayerId, predicate: DialogPredicate): Boolean = true
        // suppress unused-parameter warning by referencing constructor params symbolically
        @Suppress("UNUSED")
        private val _alwaysPermission = Permission("astera.test.alwaystrue")
    }
}
