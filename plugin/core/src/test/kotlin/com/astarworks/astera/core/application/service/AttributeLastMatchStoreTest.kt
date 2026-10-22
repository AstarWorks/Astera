package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.match.result.LastMatchRecord
import com.astarworks.astera.core.domain.match.result.MatchOutcome
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AttributeLastMatchStoreTest {
    private val containers = InMemoryPlayerAttributeContainer()
    private val store = AttributeLastMatchStore { containers }
    private val player = PlayerId(UUID.randomUUID())

    private val record = LastMatchRecord(
        matchId = "m1", endedAtEpochSec = 1_000L, modeKey = "norma", outcome = MatchOutcome.WIN,
        teams = listOf("red" to 3, "blue" to 1), kills = 4, deaths = 2, assists = 1, objective = 0,
        mvp = true, star = 145L, gold = 30L, xp = 300L, level = 3, xpIntoLevel = 20L, xpForNextLevel = 300L,
    )

    @Test
    fun `record round-trips through the attribute container`() {
        assertThat(store.find(player)).isNull()
        store.save(player, record)
        assertThat(store.find(player)).isEqualTo(record)
    }

    @Test
    fun `recap flag is set once and a newer save resets it`() {
        store.save(player, record)
        assertThat(store.recapShown(player)).isFalse()
        store.markRecapShown(player)
        assertThat(store.recapShown(player)).isTrue()
        store.save(player, record.copy(matchId = "m2"))
        assertThat(store.recapShown(player)).isFalse()
    }
}
