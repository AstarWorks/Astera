package com.astarworks.astera.core.domain.inventory

import com.astarworks.astera.core.domain.item.ItemId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SlotTypeLockRuleTest {

    @Test
    fun `PERMISSIVE rule accepts every (slot, item) combination`() {
        val rule = SlotTypeLockRule.PERMISSIVE
        assertThat(rule.allows(0, ItemId("sword"))).isTrue
        assertThat(rule.allows(39, ItemId("sword"))).isTrue
        assertThat(rule.allows(40, ItemId("helmet"))).isTrue
    }

    @Test
    fun `default rule accepts armor in matching slot`() {
        val rule = DefaultSlotTypeLockRule
        assertThat(rule.allows(36, ItemId("iron-boots"))).isTrue
        assertThat(rule.allows(37, ItemId("phoenix-leggings"))).isTrue
        assertThat(rule.allows(38, ItemId("obsidian-chestplate"))).isTrue
        assertThat(rule.allows(39, ItemId("dragon-helmet"))).isTrue
    }

    @Test
    fun `default rule rejects non-armor in armor slot`() {
        val rule = DefaultSlotTypeLockRule
        assertThat(rule.allows(36, ItemId("sword"))).isFalse
        assertThat(rule.allows(37, ItemId("bow"))).isFalse
        assertThat(rule.allows(38, ItemId("apple"))).isFalse
        assertThat(rule.allows(39, ItemId("shield-of-mending"))).isFalse
    }

    @Test
    fun `default rule allows anything in hotbar, main, off-hand`() {
        val rule = DefaultSlotTypeLockRule
        for (slot in (0..35) + listOf(40)) {
            assertThat(rule.allows(slot, ItemId("sword"))).isTrue
            assertThat(rule.allows(slot, ItemId("apple"))).isTrue
            assertThat(rule.allows(slot, ItemId("helmet"))).isTrue
        }
    }
}
