package com.astarworks.astera.core.domain.cooldown

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSkillBinding
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.SkillTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ItemCooldownSweepTest {

    private fun binding(trigger: SkillTrigger) = ItemSkillBinding(trigger, SkillId("s-${trigger.name.lowercase().replace('_', '-')}"))

    @Test
    fun `the group is unique per item and a valid namespaced key`() {
        assertThat(ItemCooldownSweep.groupKey(ItemId("pistol"))).isEqualTo("astera:cooldown/pistol")
        assertThat(ItemCooldownSweep.groupKey(ItemId("rifle"))).isNotEqualTo(ItemCooldownSweep.groupKey(ItemId("pistol")))
    }

    @Test
    fun `the primary trigger is right click, then left click, sneak variants last`() {
        assertThat(ItemCooldownSweep.primaryTrigger(listOf(binding(SkillTrigger.DROP), binding(SkillTrigger.RIGHT_CLICK))))
            .isEqualTo(SkillTrigger.RIGHT_CLICK)
        assertThat(ItemCooldownSweep.primaryTrigger(listOf(binding(SkillTrigger.SNEAK_RIGHT), binding(SkillTrigger.LEFT_CLICK))))
            .isEqualTo(SkillTrigger.LEFT_CLICK)
        assertThat(ItemCooldownSweep.primaryTrigger(listOf(binding(SkillTrigger.SNEAK_LEFT), binding(SkillTrigger.SNEAK_RIGHT))))
            .isEqualTo(SkillTrigger.SNEAK_RIGHT)
        assertThat(ItemCooldownSweep.primaryTrigger(listOf(binding(SkillTrigger.DROP)))).isNull()
        assertThat(ItemCooldownSweep.primaryTrigger(emptyList())).isNull()
    }

    @Test
    fun `the sweep runs for exactly the primary binding's cooldown`() {
        assertThat(ItemCooldownSweep.sweepTicks(SkillTrigger.RIGHT_CLICK, SkillTrigger.RIGHT_CLICK, 6, null)).isEqualTo(6)
        assertThat(ItemCooldownSweep.sweepTicks(SkillTrigger.RIGHT_CLICK, SkillTrigger.RIGHT_CLICK, 200, null)).isEqualTo(200)
    }

    @Test
    fun `a secondary binding leaves the sweep alone`() {
        assertThat(ItemCooldownSweep.sweepTicks(SkillTrigger.RIGHT_CLICK, SkillTrigger.SNEAK_RIGHT, 80, null)).isZero()
        assertThat(ItemCooldownSweep.sweepTicks(SkillTrigger.RIGHT_CLICK, null, 80, null)).isZero()
        assertThat(ItemCooldownSweep.sweepTicks(null, SkillTrigger.RIGHT_CLICK, 80, null)).isZero()
    }

    @Test
    fun `a reload sweeps for the longer of its reload and cooldown, whatever the primary is`() {
        assertThat(ItemCooldownSweep.sweepTicks(SkillTrigger.RIGHT_CLICK, SkillTrigger.DROP, 30, 30)).isEqualTo(30)
        assertThat(ItemCooldownSweep.sweepTicks(SkillTrigger.RIGHT_CLICK, SkillTrigger.DROP, 10, 45)).isEqualTo(45)
        assertThat(ItemCooldownSweep.sweepTicks(SkillTrigger.RIGHT_CLICK, SkillTrigger.DROP, 0, 45)).isEqualTo(45)
    }
}
