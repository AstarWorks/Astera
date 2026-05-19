package com.astarworks.astera.combat.application.usecase.cost

import com.astarworks.astera.combat.domain.event.ManaConsumed
import com.astarworks.astera.combat.domain.event.ManaConsumptionSource
import com.astarworks.astera.combat.domain.resource.ManaError
import com.astarworks.astera.combat.testfixtures.FakeManaPoolService
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class ConsumeManaUseCaseTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val skillId = SkillId("fireball")
    private val source = ManaConsumptionSource.Skill(skillId)

    private lateinit var manaService: FakeManaPoolService
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var useCase: ConsumeManaUseCase

    @BeforeEach
    fun setUp() {
        manaService = FakeManaPoolService()
        eventBus = FakeDomainEventBus()
        useCase = ConsumeManaUseCase(manaService = manaService, eventBus = eventBus)
    }

    @Test
    fun `success - publishes ManaConsumed with remaining and source`() {
        manaService.init(playerId, max = 100L, regenPerSec = 1.0)

        val result = useCase.execute(playerId, source, amount = 30L)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value.current).isEqualTo(70L)

        val consumed = eventBus.publishedOfType<ManaConsumed>()
        assertThat(consumed).hasSize(1)
        assertThat(consumed[0].playerId).isEqualTo(playerId)
        assertThat(consumed[0].amount).isEqualTo(30L)
        assertThat(consumed[0].remaining).isEqualTo(70L)
        assertThat(consumed[0].source).isEqualTo(source)
    }

    @Test
    fun `insufficient - returns Failure and publishes no event`() {
        manaService.init(playerId, max = 20L, regenPerSec = 1.0)

        val result = useCase.execute(playerId, source, amount = 50L)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(ManaError.Insufficient::class.java)
        assertThat((error as ManaError.Insufficient).have).isEqualTo(20L)
        assertThat(error.tried).isEqualTo(50L)

        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `uninitialized player - returns Failure and publishes no event`() {
        // No init() call.

        val result = useCase.execute(playerId, source, amount = 5L)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error)
            .isInstanceOf(ManaError.PlayerNotInitialized::class.java)
        assertThat(eventBus.published).isEmpty()
    }
}
