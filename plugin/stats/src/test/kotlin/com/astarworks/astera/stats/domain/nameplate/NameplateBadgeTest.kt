package com.astarworks.astera.stats.domain.nameplate

import com.astarworks.astera.core.domain.achievement.TitleId
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class NameplateBadgeTest {

    private val player = PlayerId(UUID.randomUUID())

    @Test
    fun `empty badge is isEmpty`() {
        assertThat(NameplateBadge.empty(player).isEmpty).isTrue()
    }

    @Test
    fun `populated badge is not isEmpty`() {
        val b = NameplateBadge(
            playerId = player,
            titleId = null,
            titleDisplayKey = null,
            certificationTier = 0,
            powerIndexTotal = 1L,
            milestoneKey = null,
        )
        assertThat(b.isEmpty).isFalse()
    }

    @Test
    fun `negative certificationTier rejected`() {
        assertThatThrownBy {
            NameplateBadge(player, null, null, -1, 0L, null)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `negative powerIndexTotal rejected`() {
        assertThatThrownBy {
            NameplateBadge(player, null, null, 0, -1L, null)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `titleId without titleDisplayKey rejected`() {
        assertThatThrownBy {
            NameplateBadge(
                playerId = player,
                titleId = TitleId("champion"),
                titleDisplayKey = null,
                certificationTier = 0,
                powerIndexTotal = 0L,
                milestoneKey = null,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `titleId with displayKey accepted`() {
        val b = NameplateBadge(
            playerId = player,
            titleId = TitleId("champion"),
            titleDisplayKey = MessageKey("astera.stats.title.champion"),
            certificationTier = 1,
            powerIndexTotal = 100L,
            milestoneKey = MessageKey("astera.stats.milestone.kills-1000"),
        )
        assertThat(b.isEmpty).isFalse()
        assertThat(b.titleId).isEqualTo(TitleId("champion"))
        assertThat(b.titleDisplayKey).isEqualTo(MessageKey("astera.stats.title.champion"))
    }
}
