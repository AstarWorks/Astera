package com.astarworks.astera.stats.application.service.season

import com.astarworks.astera.stats.domain.season.SeasonId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemorySeasonRepositoryTest {

    private val epoch = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `fresh repository has a default active season`() {
        val repo = InMemorySeasonRepository(clock = { epoch })
        val current = repo.current()!!
        assertThat(current.id).isEqualTo(SeasonId("season-1"))
        assertThat(current.label).isEqualTo("Season 1")
        assertThat(current.startedAt).isEqualTo(epoch)
        assertThat(current.isActive).isTrue()
    }

    @Test
    fun `start closes the prior active season and opens a new one`() {
        val repo = InMemorySeasonRepository(clock = { epoch })
        val resetAt = epoch.plusSeconds(3600)

        val opened = repo.start("Summer Cup", resetAt)

        assertThat(opened.isActive).isTrue()
        assertThat(opened.startedAt).isEqualTo(resetAt)
        assertThat(repo.current()).isEqualTo(opened)

        val prior = repo.all().single { it.id == SeasonId("season-1") }
        assertThat(prior.isActive).isFalse()
        assertThat(prior.endedAt).isEqualTo(resetAt)
    }

    @Test
    fun `start slugifies the label to a lower-kebab-case id`() {
        val repo = InMemorySeasonRepository(clock = { epoch })
        val opened = repo.start("  Summer Cup 2026!! ", epoch.plusSeconds(10))
        assertThat(opened.id).isEqualTo(SeasonId("summer-cup-2026"))
    }

    @Test
    fun `start prefixes the slug when the label starts with a non-letter`() {
        val repo = InMemorySeasonRepository(clock = { epoch })
        val opened = repo.start("2026 Finals", epoch.plusSeconds(10))
        assertThat(opened.id).isEqualTo(SeasonId("season-2026-finals"))
    }

    @Test
    fun `start suffixes colliding slugs`() {
        val repo = InMemorySeasonRepository(clock = { epoch })
        val first = repo.start("Ranked", epoch.plusSeconds(10))
        val second = repo.start("Ranked", epoch.plusSeconds(20))
        val third = repo.start("Ranked", epoch.plusSeconds(30))

        assertThat(first.id).isEqualTo(SeasonId("ranked"))
        assertThat(second.id).isEqualTo(SeasonId("ranked-2"))
        assertThat(third.id).isEqualTo(SeasonId("ranked-3"))
    }

    @Test
    fun `all returns seasons most-recent-first`() {
        val repo = InMemorySeasonRepository(clock = { epoch })
        repo.start("Two", epoch.plusSeconds(10))
        repo.start("Three", epoch.plusSeconds(20))

        assertThat(repo.all().map { it.label }).containsExactly("Three", "Two", "Season 1")
    }
}
