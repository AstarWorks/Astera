package com.astarworks.astera.core.application.eventbus.listeners.onboarding

import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.ITutorialRegistry
import com.astarworks.astera.core.application.service.InMemoryPlayerProfileService
import com.astarworks.astera.core.domain.event.FirstTimeProfileCreated
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.StarterGrant
import com.astarworks.astera.core.domain.tutorial.TutorialScript
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.testfixtures.FakeDialogGateway
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5wireup (ADR-0116 §G3) — Welcome dialog listener test.
 */
class ShowWelcomeDialogOnFirstTimeProfileCreatedListenerTest {

    private val playerId = PlayerId(UUID.randomUUID())

    private fun makeProfileService(welcomeId: String?): InMemoryPlayerProfileService {
        return InMemoryPlayerProfileService(
            starterGrant = StarterGrant(
                starterItems = emptyList(),
                walletGrants = emptyMap(),
                tutorialAutoEnroll = false,
                tutorialScriptId = null,
                welcomeDialogId = welcomeId,
            ),
            itemRegistry = NoopItemRegistry,
            ledgerProvider = { null },
            tutorialRegistry = EmptyTutorialRegistry,
        )
    }

    @Test
    fun `handle shows the welcome dialog when one is recorded`() {
        val profileService = makeProfileService("starter-greeter")
        profileService.createFirstTime(playerId, displayName = "P", locale = null)
        val gateway = FakeDialogGateway(eventBus = FakeDomainEventBus())
        val listener = ShowWelcomeDialogOnFirstTimeProfileCreatedListener(profileService, gateway)

        listener.handle(FirstTimeProfileCreated(playerId = playerId, atTick = 1L))

        assertThat(gateway.showCalls).hasSize(1)
        val shown = gateway.showCalls.first()
        assertThat(shown.playerId).isEqualTo(playerId)
        assertThat(shown.spec.titleKey.value).isEqualTo("astera.dialog.welcome.starter-greeter.title")
    }

    @Test
    fun `handle no-ops when grant did not configure a welcome dialog`() {
        val profileService = makeProfileService(null)
        profileService.createFirstTime(playerId, displayName = "P", locale = null)
        val gateway = FakeDialogGateway(eventBus = FakeDomainEventBus())
        val listener = ShowWelcomeDialogOnFirstTimeProfileCreatedListener(profileService, gateway)

        listener.handle(FirstTimeProfileCreated(playerId = playerId, atTick = 1L))

        assertThat(gateway.showCalls).isEmpty()
    }

    private object NoopItemRegistry : IItemRegistry {
        override fun find(key: com.astarworks.astera.core.domain.item.ItemId): com.astarworks.astera.core.domain.item.ItemSpec? = null
        override fun all(): Collection<com.astarworks.astera.core.domain.item.ItemSpec> = emptyList()
    }

    private object EmptyTutorialRegistry : ITutorialRegistry {
        override fun find(key: TutorialScriptId): TutorialScript? = null
        override fun all(): Collection<TutorialScript> = emptyList()
    }
}
