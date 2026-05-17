package com.astarworks.astera.combat.application.usecase.status

import com.astarworks.astera.combat.testfixtures.FakeCooldownTracker
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class TickCooldownUseCaseTest {

    private val playerId = PlayerId(UUID.randomUUID())

    private lateinit var cooldownTracker: FakeCooldownTracker
    private lateinit var scheduler: FakeScheduler
    private lateinit var useCase: TickCooldownUseCase

    @BeforeEach
    fun setUp() {
        cooldownTracker = FakeCooldownTracker()
        scheduler = FakeScheduler(startTick = 0L)
        useCase = TickCooldownUseCase(
            cooldownTracker = cooldownTracker,
            scheduler = scheduler,
        )
    }

    @Test
    fun `execute returns empty when no cooldowns active`() {
        val expired = useCase.execute()
        assertThat(expired).isEmpty()
    }

    @Test
    fun `execute delegates currentTick from scheduler`() {
        scheduler.advanceTicks(42)
        useCase.execute()
        assertThat(cooldownTracker.ticked).containsExactly(42L)
    }

    @Test
    fun `execute returns expired pairs from tracker`() {
        val key = CooldownKey.Skill("fireball")
        cooldownTracker.start(playerId, key, 10L)   // expires at tick 10

        // Advance to tick 10 — the FakeCooldownTracker sees this via its own currentTick
        cooldownTracker.currentTick = 10L
        scheduler.advanceTicks(10)

        val expired = useCase.execute()
        assertThat(expired).containsExactly(Pair(playerId, key))
    }

    @Test
    fun `execute returns empty when cooldowns not yet expired`() {
        val key = CooldownKey.Weapon("iron-sword")
        cooldownTracker.start(playerId, key, 20L)   // expires at tick 20

        cooldownTracker.currentTick = 5L
        scheduler.advanceTicks(5)

        val expired = useCase.execute()
        assertThat(expired).isEmpty()
    }

    @Test
    fun `execute can be called multiple times and tracks tick progression`() {
        useCase.execute() // tick 0
        scheduler.advanceTicks(1)
        cooldownTracker.currentTick = 1L
        useCase.execute() // tick 1

        assertThat(cooldownTracker.ticked).containsExactly(0L, 1L)
    }
}
