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
import com.astarworks.astera.testfixtures.FakeScheduler
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
    private val scheduler = FakeScheduler()
    private lateinit var useCase: ApplyStatusUseCase

    @BeforeEach
    fun setUp() {
        registry = FakeStatusEffectRegistry()
        container = FakeStatusEffectContainer()
        eventBus = FakeDomainEventBus()
        useCase = ApplyStatusUseCase(
            effectRegistry = registry,
            effectContainer = container,
            scheduler = scheduler,
        )
    }

    @Test
    fun `happy path - applies effect with the scheduler tick and leaves publishing to the container`() {
        registry.register(burnSpec)
        scheduler.advanceTicks(7)

        val result = useCase.execute(targetId, burnId, durationTicks = 60)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val instance = (result as Result.Success).value
        assertThat(instance.spec).isEqualTo(burnSpec)
        assertThat(instance.targetPlayerId).isEqualTo(targetId)
        assertThat(instance.remainingTicks).isEqualTo(60)
        assertThat(instance.currentStacks).isEqualTo(1)

        assertThat(container.has(targetId, burnId)).isTrue()

        // The container owns StatusApplied (with merged stacks and the real tick);
        // the use case publishing as well made every application fire twice.
        assertThat(eventBus.publishedOfType<StatusApplied>()).isEmpty()
        assertThat(instance.appliedAtTick).isEqualTo(7L)
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
    @Test
    fun `omitted duration uses the YAML status default and explicit duration wins`() {
        val dto = com.astarworks.astera.combat.application.config.StatusEffectYamlConfig(
            id = "burn", displayNameKey = "test.burn", kind = "burn", defaultDurationTicks = 240,
            params = com.astarworks.astera.combat.application.config.StatusEffectParamsYaml.BurnParamsYaml(2.0),
        )
        registry.register(dto.toSpec())
        val configured = useCase.execute(targetId, burnId)
        check(configured is Result.Success)
        assertThat(configured.value.remainingTicks).isEqualTo(240)
        val overridden = useCase.execute(targetId, burnId, durationTicks = 20)
        check(overridden is Result.Success)
        assertThat(overridden.value.remainingTicks).isEqualTo(20)
    }
}
