package com.astarworks.astera.stats.application.usecase.nameplate

import com.astarworks.astera.core.domain.achievement.TitleId
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.service.nameplate.NameplateBadgeRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class RecomputeNameplateBadgeUseCaseTest {

    private val player = PlayerId(UUID.randomUUID())

    @Test
    fun `fresh player gets an empty badge`() {
        val cache = NameplateBadgeRegistry()
        val uc = RecomputeNameplateBadgeUseCase(
            cache = cache,
            equippedTitleProvider = { null },
        )
        val b = uc.recompute(player)
        assertThat(b.isEmpty).isTrue()
        assertThat(cache.find(player)).isEqualTo(b)
    }

    @Test
    fun `title provider populates titleId and displayKey`() {
        val cache = NameplateBadgeRegistry()
        val uc = RecomputeNameplateBadgeUseCase(
            cache = cache,
            equippedTitleProvider = {
                RecomputeNameplateBadgeUseCase.EquippedTitle(
                    id = TitleId("champion"),
                    displayKey = MessageKey("astera.stats.title.champion"),
                )
            },
        )
        val b = uc.recompute(player)
        assertThat(b.titleId).isEqualTo(TitleId("champion"))
        assertThat(b.titleDisplayKey).isEqualTo(MessageKey("astera.stats.title.champion"))
    }

    @Test
    fun `negative providers are clamped to zero`() {
        val cache = NameplateBadgeRegistry()
        val uc = RecomputeNameplateBadgeUseCase(
            cache = cache,
            equippedTitleProvider = { null },
            certificationTierProvider = { -5 },
            powerIndexTotalProvider = { -100L },
        )
        val b = uc.recompute(player)
        assertThat(b.certificationTier).isZero()
        assertThat(b.powerIndexTotal).isZero()
    }

    @Test
    fun `milestone provider populates milestoneKey`() {
        val cache = NameplateBadgeRegistry()
        val uc = RecomputeNameplateBadgeUseCase(
            cache = cache,
            equippedTitleProvider = { null },
            milestoneKeyProvider = { MessageKey("astera.stats.milestone.kills-1000") },
        )
        val b = uc.recompute(player)
        assertThat(b.milestoneKey).isEqualTo(MessageKey("astera.stats.milestone.kills-1000"))
    }

    @Test
    fun `recompute overwrites cached badge`() {
        val cache = NameplateBadgeRegistry()
        var px = 100L
        val uc = RecomputeNameplateBadgeUseCase(
            cache = cache,
            equippedTitleProvider = { null },
            powerIndexTotalProvider = { px },
        )
        uc.recompute(player)
        px = 500L
        uc.recompute(player)
        assertThat(cache.find(player)?.powerIndexTotal).isEqualTo(500L)
    }
}
