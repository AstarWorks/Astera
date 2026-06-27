package com.astarworks.astera.stats.application.usecase.season

import com.astarworks.astera.stats.application.service.season.InMemorySeasonRepository
import com.astarworks.astera.stats.domain.season.SeasonId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ResetSeasonUseCaseTest {

    private val resetAt = Instant.parse("2026-03-01T00:00:00Z")

    private fun repo() = InMemorySeasonRepository(clock = { Instant.parse("2026-01-01T00:00:00Z") })

    @Test
    fun `execute opens a new active season at the clock instant`() {
        val seasons = repo()
        val useCase = ResetSeasonUseCase(seasons, clock = { resetAt })

        val result = useCase.execute("Spring Cup")

        val opened = result.getOrNull()!!
        assertThat(opened.id).isEqualTo(SeasonId("spring-cup"))
        assertThat(opened.startedAt).isEqualTo(resetAt)
        assertThat(seasons.current()).isEqualTo(opened)
    }

    @Test
    fun `execute closes the previously active season`() {
        val seasons = repo()
        val useCase = ResetSeasonUseCase(seasons, clock = { resetAt })

        useCase.execute("Spring Cup")

        val prior = seasons.all().single { it.id == SeasonId("season-1") }
        assertThat(prior.endedAt).isEqualTo(resetAt)
        assertThat(prior.isActive).isFalse()
    }

    @Test
    fun `execute fails with BlankLabel for a blank label`() {
        val useCase = ResetSeasonUseCase(repo(), clock = { resetAt })
        assertThat(useCase.execute("   ").errorOrNull())
            .isEqualTo(ResetSeasonUseCase.SeasonError.BlankLabel)
    }
}
