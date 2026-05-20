package com.astarworks.astera.combat.application.usecase.status

import com.astarworks.astera.combat.domain.event.StatusApplied
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class ApplyStatusUseCaseTest {

    private val targetId = PlayerId(UUID.randomUUID())
    private val burnId = StatusEffectId("burn")

    private val burnSpec = StatusEffectSpec(
        id = burnId,
        displayNameKey = MessageKey("test.burn"),
        kind = StatusKind.BURN,
        magnitude = 2.0,
        stackable = false,
        maxStacks = 1,
    )

    private lateinit var registry: FakeStatusEffectRegistry
    private lateinit var container: FakeStatusEffectContainer
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var useCase: ApplyStatusUseCase

    @BeforeEach
    fun setUp() {
        registry = FakeStatusEffectRegistry()
        container = FakeStatusEffectContainer()
        eventBus = FakeDomainEventBus()
        useCase = ApplyStatusUseCase(
            effectRegistry = registry,
            effectContainer = container,
            eventBus = eventBus,
        )
    }

    @Test
    fun `happy path - applies effect and publishes StatusApplied`() {
        registry.register(burnSpec)

        val result = useCase.execute(targetId, burnId, durationTicks = 60)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val instance = (result as Result.Success).value
        assertThat(instance.spec).isEqualTo(burnSpec)
        assertThat(instance.targetPlayerId).isEqualTo(targetId)
        assertThat(instance.remainingTicks).isEqualTo(60)
        assertThat(instance.currentStacks).isEqualTo(1)

        assertThat(container.has(targetId, burnId)).isTrue()

        val events = eventBus.publishedOfType<StatusApplied>()
        assertThat(events).hasSize(1)
        assertThat(events[0].target).isEqualTo(targetId)
        assertThat(events[0].effect).isEqualTo(instance)
    }

    @Test
    fun `effect not found - returns EffectNotFound, no event published`() {
        // Do NOT register any spec

        val result = useCase.execute(targetId, burnId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(ApplyStatusError.EffectNotFound::class.java)
        assertThat((error as ApplyStatusError.EffectNotFound).effectId).isEqualTo(burnId)
        assertThat(eventBus.published).isEmpty()
    }
}
