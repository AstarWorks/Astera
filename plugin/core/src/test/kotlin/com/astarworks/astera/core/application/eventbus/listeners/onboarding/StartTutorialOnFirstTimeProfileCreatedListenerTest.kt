package com.astarworks.astera.core.application.eventbus.listeners.onboarding

import com.astarworks.astera.core.application.port.inbound.ITutorialSequencerService
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.ITutorialRegistry
import com.astarworks.astera.core.application.service.InMemoryPlayerProfileService
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.FirstTimeProfileCreated
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.StarterGrant
import com.astarworks.astera.core.domain.tutorial.TutorialScript
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.core.domain.tutorial.TutorialSequencerError
import com.astarworks.astera.core.domain.tutorial.TutorialStep
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5wireup (ADR-0116 §G2) — Tutorial auto-start listener test.
 */
class StartTutorialOnFirstTimeProfileCreatedListenerTest {

    private val playerId = PlayerId(UUID.randomUUID())

    private fun makeProfileService(grant: StarterGrant): InMemoryPlayerProfileService {
        // The tutorial registry needs to recognize the script id for the
        // service to record the enrollment; supply a single-script registry.
        val scriptId = grant.tutorialScriptId
        val registry: ITutorialRegistry = if (scriptId != null) {
            FakeSingletonTutorialRegistry(TutorialScriptId(scriptId))
        } else {
            FakeSingletonTutorialRegistry(null)
        }
        return InMemoryPlayerProfileService(
            starterGrant = grant,
            itemRegistry = NoopItemRegistry,
            ledgerProvider = { null },
            tutorialRegistry = registry,
        )
    }

    @Test
    fun `handle starts the recorded tutorial sequence`() {
        val grant = StarterGrant(
            starterItems = emptyList(),
            walletGrants = emptyMap(),
            tutorialAutoEnroll = true,
            tutorialScriptId = "tutorial-getting-started",
            welcomeDialogId = null,
        )
        val profileService = makeProfileService(grant)
        // Create a profile so the listener finds an enrollment record.
        profileService.createFirstTime(playerId, displayName = "P", locale = null)
        val sequencer = FakeSequencer()
        val listener = StartTutorialOnFirstTimeProfileCreatedListener(profileService, sequencer)

        listener.handle(FirstTimeProfileCreated(playerId = playerId, atTick = 1L))

        assertThat(sequencer.startCalls).hasSize(1)
        assertThat(sequencer.startCalls.first().first).isEqualTo(playerId)
        assertThat(sequencer.startCalls.first().second.value).isEqualTo("tutorial-getting-started")
    }

    @Test
    fun `handle no-ops when grant did not record a tutorial`() {
        val grant = StarterGrant.EMPTY
        val profileService = makeProfileService(grant)
        profileService.createFirstTime(playerId, displayName = "P", locale = null)
        val sequencer = FakeSequencer()
        val listener = StartTutorialOnFirstTimeProfileCreatedListener(profileService, sequencer)

        listener.handle(FirstTimeProfileCreated(playerId = playerId, atTick = 1L))

        assertThat(sequencer.startCalls).isEmpty()
    }

    @Test
    fun `handle swallows sequencer failure`() {
        val grant = StarterGrant(
            starterItems = emptyList(),
            walletGrants = emptyMap(),
            tutorialAutoEnroll = true,
            tutorialScriptId = "tutorial-getting-started",
            welcomeDialogId = null,
        )
        val profileService = makeProfileService(grant)
        profileService.createFirstTime(playerId, displayName = "P", locale = null)
        val sequencer = FakeSequencer().apply {
            nextResult = Result.failure(TutorialSequencerError.NotInCatalog(
                TutorialScriptId("tutorial-getting-started"),
            ))
        }
        val listener = StartTutorialOnFirstTimeProfileCreatedListener(profileService, sequencer)

        // Should not throw.
        listener.handle(FirstTimeProfileCreated(playerId = playerId, atTick = 1L))

        assertThat(sequencer.startCalls).hasSize(1)
    }

    private class FakeSequencer : ITutorialSequencerService {
        val startCalls: MutableList<Pair<PlayerId, TutorialScriptId>> = mutableListOf()
        var nextResult: Result<Unit, TutorialSequencerError> = Result.success(Unit)
        override fun startSequence(
            playerId: PlayerId,
            scriptId: TutorialScriptId,
        ): Result<Unit, TutorialSequencerError> {
            startCalls += playerId to scriptId
            return nextResult
        }
        override fun currentStep(playerId: PlayerId): TutorialStep? = null
        override fun advance(playerId: PlayerId): Result<TutorialStep?, TutorialSequencerError> = Result.success(null)
        override fun cancel(playerId: PlayerId): Result<Unit, TutorialSequencerError> = Result.success(Unit)
    }

    private class FakeSingletonTutorialRegistry(
        private val id: TutorialScriptId?,
    ) : ITutorialRegistry {
        override fun find(key: TutorialScriptId): TutorialScript? =
            if (id != null && key == id) {
                TutorialScript(
                    id = id,
                    displayKey = com.astarworks.astera.core.domain.i18n.MessageKey("astera.tutorial.title"),
                    steps = listOf(TutorialStep.SendMessage(com.astarworks.astera.core.domain.i18n.MessageKey("a.b"))),
                )
            } else null
        override fun all(): Collection<TutorialScript> = emptyList()
    }

    private object NoopItemRegistry : IItemRegistry {
        override fun find(key: com.astarworks.astera.core.domain.item.ItemId): com.astarworks.astera.core.domain.item.ItemSpec? = null
        override fun all(): Collection<com.astarworks.astera.core.domain.item.ItemSpec> = emptyList()
    }
}
