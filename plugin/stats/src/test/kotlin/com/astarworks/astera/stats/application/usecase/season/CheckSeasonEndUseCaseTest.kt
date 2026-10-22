package com.astarworks.astera.stats.application.usecase.season

import com.astarworks.astera.stats.application.config.SeasonWindowYamlConfig
import com.astarworks.astera.stats.application.config.SeasonsYamlConfig
import com.astarworks.astera.stats.application.service.season.InMemorySeasonRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class CheckSeasonEndUseCaseTest {
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val end = Instant.parse("2026-04-01T00:00:00Z")
    private val windows = listOf(SeasonWindowYamlConfig("spring", start.toString(), end.toString(), "Summer"))

    @Test
    fun `end boundary resets once even after reconstructing the checker`() {
        val repo = InMemorySeasonRepository { start }
        val announcements = mutableListOf<String>()
        fun checker() = CheckSeasonEndUseCase(windows, ResetSeasonUseCase(repo)) { id, _ -> announcements += id }
        assertThat(checker().execute(end.minusSeconds(1)).getOrNull()).isZero()
        assertThat(repo.all()).hasSize(1)
        assertThat(checker().execute(end).getOrNull()).isEqualTo(1)
        assertThat(checker().execute(end.plusSeconds(60)).getOrNull()).isZero()
        assertThat(repo.all()).hasSize(2)
        assertThat(repo.current()!!.label).isEqualTo("Summer")
        assertThat(announcements).containsExactly("spring")
    }

    @Test
    fun `overlap reversed dates and duplicate keys are rejected`() {
        assertThatThrownBy { SeasonsYamlConfig(windows + windows) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SeasonsYamlConfig(listOf(windows.single().copy(end = start.toString()))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SeasonsYamlConfig(windows + windows.single().copy(id = "other")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
