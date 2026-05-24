package com.astarworks.astera.core.domain.survival

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class GatheringSpotTest {

    private val name = LocalizedText(byLocale = mapOf(LocaleCode("en") to "Test"))
    private val yields = listOf(
        GatheringYield(itemId = ItemId("example-sword"), weight = 1, minCount = 1, maxCount = 1),
    )

    private fun spot(
        kind: GatheringSpotKind = GatheringSpotKind.Mining,
        trigger: GatheringSkillTrigger = GatheringSkillTrigger.PickaxeSwing,
        cooldownTicks: Long = 100L,
        spotYields: List<GatheringYield> = yields,
    ): GatheringSpot = GatheringSpot(
        id = GatheringSpotId("test-spot"),
        displayName = name,
        kind = kind,
        trigger = trigger,
        world = WorldId("w"),
        position = Vec3.ZERO,
        yields = spotYields,
        cooldownTicks = cooldownTicks,
    )

    @Test
    fun `valid spot constructs`() {
        val s = spot()
        assertThat(s.id.value).isEqualTo("test-spot")
    }

    @Test
    fun `kind ↔ trigger mismatch is rejected`() {
        assertThatThrownBy {
            spot(kind = GatheringSpotKind.Fishing, trigger = GatheringSkillTrigger.PickaxeSwing)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `kind matches its native trigger`() {
        assertThat(GatheringSpotKind.Fishing.matches(GatheringSkillTrigger.FishingRodCast)).isTrue
        assertThat(GatheringSpotKind.Mining.matches(GatheringSkillTrigger.PickaxeSwing)).isTrue
        assertThat(GatheringSpotKind.Logging.matches(GatheringSkillTrigger.AxeSwing)).isTrue
        assertThat(GatheringSpotKind.Digging.matches(GatheringSkillTrigger.ShovelDig)).isTrue
        assertThat(GatheringSpotKind.Mining.matches(GatheringSkillTrigger.AxeSwing)).isFalse
    }

    @Test
    fun `cooldown zero is rejected`() {
        assertThatThrownBy { spot(cooldownTicks = 0L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `empty yields rejected`() {
        assertThatThrownBy { spot(spotYields = emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `yield with non-positive weight rejected`() {
        assertThatThrownBy {
            GatheringYield(itemId = ItemId("example-sword"), weight = 0, minCount = 1, maxCount = 1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `yield with maxCount below minCount rejected`() {
        assertThatThrownBy {
            GatheringYield(itemId = ItemId("example-sword"), weight = 1, minCount = 3, maxCount = 1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
