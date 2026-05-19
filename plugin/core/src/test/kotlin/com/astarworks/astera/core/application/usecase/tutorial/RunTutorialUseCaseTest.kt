package com.astarworks.astera.core.application.usecase.tutorial

import com.astarworks.astera.core.application.contents.TutorialContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.TutorialStepEntered
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialScript
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.core.domain.tutorial.TutorialStep
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [RunTutorialUseCase] (Wave 5e, RTM issue #17).
 *
 * Drives the state machine through the public API only — no peeking at the
 * private `state` map. Per-step entry is verified by capturing
 * [TutorialStepEntered] events off a real [InMemoryDomainEventBus], which
 * also doubles as a contract test for the publish-step-on-advance behaviour.
 */
class RunTutorialUseCaseTest {

    private lateinit var registry: TutorialContents
    private lateinit var events: InMemoryDomainEventBus
    private lateinit var captured: MutableList<TutorialStepEntered>
    private lateinit var useCase: RunTutorialUseCase

    private val scriptId = TutorialScriptId("getting-started")
    private val playerId = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

    private val script = TutorialScript(
        id = scriptId,
        displayKey = MessageKey("astera.tutorial.getting_started.title"),
        steps = listOf(
            TutorialStep.SendMessage(MessageKey("astera.tutorial.welcome")),
            TutorialStep.GiveItem(itemId = "astera:example-pistol", count = 1),
            TutorialStep.WaitForAction(actionId = "fire_weapon", timeoutSec = 60L),
            TutorialStep.SendMessage(MessageKey("astera.tutorial.complete")),
        ),
    )

    @BeforeEach
    fun setUp() {
        registry = TutorialContents()
        registry.register(script)
        events = InMemoryDomainEventBus()
        captured = mutableListOf()
        events.subscribe(TutorialStepEntered::class) { captured += it }
        useCase = RunTutorialUseCase(registry = registry, events = events)
    }

    @Test
    fun `start publishes step 0 event and stores state`() {
        val result = useCase.start(playerId, scriptId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(captured).hasSize(1)
        val event = captured.single()
        assertThat(event.player).isEqualTo(playerId)
        assertThat(event.scriptId).isEqualTo(scriptId)
        assertThat(event.stepIndex).isEqualTo(0)
        assertThat(event.step).isEqualTo(script.steps[0])
        assertThat(useCase.currentStep(playerId)).isEqualTo(script.steps[0])
    }

    @Test
    fun `start fails NotInCatalog when script unknown`() {
        val result = useCase.start(playerId, TutorialScriptId("missing"))

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(TutorialError.NotInCatalog::class.java)
        assertThat((error as TutorialError.NotInCatalog).scriptId).isEqualTo(TutorialScriptId("missing"))
        assertThat(captured).isEmpty()
    }

    @Test
    fun `start fails AlreadyRunning when player is mid-script`() {
        useCase.start(playerId, scriptId)
        captured.clear()

        val result = useCase.start(playerId, scriptId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(TutorialError.AlreadyRunning::class.java)
        assertThat((error as TutorialError.AlreadyRunning).player).isEqualTo(playerId)
        assertThat(captured).isEmpty()
    }

    @Test
    fun `advance moves to the next step and publishes the event`() {
        useCase.start(playerId, scriptId)
        captured.clear()

        val result = useCase.advance(playerId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isEqualTo(script.steps[1])
        assertThat(captured).hasSize(1)
        assertThat(captured.single().stepIndex).isEqualTo(1)
        assertThat(captured.single().step).isEqualTo(script.steps[1])
        assertThat(useCase.currentStep(playerId)).isEqualTo(script.steps[1])
    }

    @Test
    fun `advance past final step returns success(null) and clears state`() {
        useCase.start(playerId, scriptId)
        // step 0 -> 1
        useCase.advance(playerId)
        // step 1 -> 2 (WaitForAction)
        useCase.advance(playerId)
        // step 2 -> 3 (final SendMessage)
        useCase.advance(playerId)
        captured.clear()

        // Past the final step — completion.
        val result = useCase.advance(playerId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isNull()
        // No new event fired for completion.
        assertThat(captured).isEmpty()
        // State cleared — currentStep is null and a fresh start succeeds.
        assertThat(useCase.currentStep(playerId)).isNull()
        assertThat(useCase.start(playerId, scriptId)).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `advance returns NotRunning when player has no active script`() {
        val result = useCase.advance(playerId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isInstanceOf(TutorialError.NotRunning::class.java)
    }

    @Test
    fun `notifyAction wakes the matching WaitForAction step`() {
        useCase.start(playerId, scriptId)
        useCase.advance(playerId) // -> step 1 GiveItem
        useCase.advance(playerId) // -> step 2 WaitForAction("fire_weapon")
        captured.clear()
        assertThat(useCase.currentStep(playerId)).isInstanceOf(TutorialStep.WaitForAction::class.java)

        val result = useCase.notifyAction(playerId, "fire_weapon")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        // Auto-advanced to step 3.
        assertThat(captured).hasSize(1)
        assertThat(captured.single().stepIndex).isEqualTo(3)
        assertThat(useCase.currentStep(playerId)).isEqualTo(script.steps[3])
    }

    @Test
    fun `notifyAction ignores non-matching action tokens`() {
        useCase.start(playerId, scriptId)
        useCase.advance(playerId) // -> step 1
        useCase.advance(playerId) // -> step 2 WaitForAction
        captured.clear()

        val result = useCase.notifyAction(playerId, "unrelated_action")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        // No advance fired.
        assertThat(captured).isEmpty()
        assertThat(useCase.currentStep(playerId)).isEqualTo(script.steps[2])
    }

    @Test
    fun `notifyAction is a no-op for non-WaitForAction steps`() {
        useCase.start(playerId, scriptId)
        // Still on step 0 (SendMessage).
        captured.clear()

        val result = useCase.notifyAction(playerId, "fire_weapon")

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(captured).isEmpty()
        assertThat(useCase.currentStep(playerId)).isEqualTo(script.steps[0])
    }

    @Test
    fun `notifyAction returns NotRunning when player has no active script`() {
        val result = useCase.notifyAction(playerId, "fire_weapon")

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isInstanceOf(TutorialError.NotRunning::class.java)
    }

    @Test
    fun `currentStep returns null when player is not running`() {
        assertThat(useCase.currentStep(playerId)).isNull()
    }
}
