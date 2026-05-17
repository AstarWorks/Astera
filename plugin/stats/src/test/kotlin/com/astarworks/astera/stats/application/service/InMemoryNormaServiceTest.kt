package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.norma.Norma
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.norma.NormaReward
import com.astarworks.astera.core.domain.norma.NormaTarget
import com.astarworks.astera.core.domain.norma.PeriodKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryNormaServiceTest {

    private lateinit var service: InMemoryNormaService
    private val playerId = PlayerId(UUID.randomUUID())
    private val normaId = NormaId("test-killer")

    private val testNorma = Norma(
        id = normaId,
        target = NormaTarget.Kills(3),
        reward = NormaReward(currencyId = "STAR", currencyAmount = 50, xp = 100),
        period = PeriodKind.Daily,
        displayKey = MessageKey("astera.norma.test-killer.name"),
    )

    @BeforeEach
    fun setUp() {
        service = InMemoryNormaService(listOf(testNorma))
    }

    @Test
    fun `activeNormasFor returns configured normas`() {
        val normas = service.activeNormasFor(playerId)
        assertThat(normas).containsExactly(testNorma)
    }

    @Test
    fun `progress is empty for new player`() {
        assertThat(service.progress(playerId)).isEmpty()
    }

    @Test
    fun `recordProgress increments kill count`() {
        service.recordProgress(playerId, NormaTarget.Kills(1), 1)
        val progress = service.progress(playerId).find { it.normaId == normaId }
        assertThat(progress).isNotNull
        assertThat(progress!!.current).isEqualTo(1L)
        assertThat(progress.completed).isFalse()
    }

    @Test
    fun `recordProgress returns empty when not yet complete`() {
        val completed = service.recordProgress(playerId, NormaTarget.Kills(1), 1)
        assertThat(completed).isEmpty()
    }

    @Test
    fun `recordProgress detects completion at threshold`() {
        service.recordProgress(playerId, NormaTarget.Kills(1), 1)
        service.recordProgress(playerId, NormaTarget.Kills(1), 1)
        val completed = service.recordProgress(playerId, NormaTarget.Kills(1), 1)
        assertThat(completed).hasSize(1)
        assertThat(completed.first().normaId).isEqualTo(normaId)
        assertThat(completed.first().completed).isTrue()
        assertThat(completed.first().completedAt).isNotNull()
    }

    @Test
    fun `recordProgress does not re-complete already completed norma`() {
        repeat(3) { service.recordProgress(playerId, NormaTarget.Kills(1), 1) }
        // 4th kill should not trigger completion again
        val completed = service.recordProgress(playerId, NormaTarget.Kills(1), 1)
        assertThat(completed).isEmpty()
    }

    @Test
    fun `recordProgress ignores different target types`() {
        val newlyCompleted = service.recordProgress(playerId, NormaTarget.Damage(100), 100)
        assertThat(newlyCompleted).isEmpty()
        assertThat(service.progress(playerId)).isEmpty()
    }

    @Test
    fun `markComplete sets completed=true`() {
        service.recordProgress(playerId, NormaTarget.Kills(1), 1) // partial progress
        val result = service.markComplete(playerId, normaId)
        assertThat(result).isNotNull
        assertThat(result!!.completed).isTrue()
        assertThat(result.completedAt).isNotNull()
    }

    @Test
    fun `markComplete returns null for unknown norma`() {
        val result = service.markComplete(playerId, NormaId("unknown-norma"))
        assertThat(result).isNull()
    }
}
