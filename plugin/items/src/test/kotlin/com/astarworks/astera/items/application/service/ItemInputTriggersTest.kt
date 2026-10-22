package com.astarworks.astera.items.application.service

import com.astarworks.astera.core.adapter.api.event.IMcEvent.InputKind
import com.astarworks.astera.core.domain.skill.SkillTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ItemInputTriggersTest {

    @Test
    fun `left click maps to LEFT_CLICK, sneaking prefers SNEAK_LEFT then falls back`() {
        assertThat(ItemInputTriggers.candidates(InputKind.LEFT_CLICK, sneaking = false))
            .containsExactly(SkillTrigger.LEFT_CLICK)
        assertThat(ItemInputTriggers.candidates(InputKind.LEFT_CLICK, sneaking = true))
            .containsExactly(SkillTrigger.SNEAK_LEFT, SkillTrigger.LEFT_CLICK)
    }

    @Test
    fun `right click maps to RIGHT_CLICK, sneaking prefers SNEAK_RIGHT then falls back`() {
        assertThat(ItemInputTriggers.candidates(InputKind.RIGHT_CLICK, sneaking = false))
            .containsExactly(SkillTrigger.RIGHT_CLICK)
        assertThat(ItemInputTriggers.candidates(InputKind.RIGHT_CLICK, sneaking = true))
            .containsExactly(SkillTrigger.SNEAK_RIGHT, SkillTrigger.RIGHT_CLICK)
    }

    @Test
    fun `held right click tries HOLD first`() {
        assertThat(ItemInputTriggers.candidates(InputKind.RIGHT_CLICK, sneaking = false, heldRepeat = true))
            .containsExactly(SkillTrigger.HOLD, SkillTrigger.RIGHT_CLICK)
    }

    @Test
    fun `drop maps to DROP and sneaking drop prefers SNEAK_DROP then falls back, shoot is one to one`() {
        assertThat(ItemInputTriggers.candidates(InputKind.DROP, sneaking = false))
            .containsExactly(SkillTrigger.DROP)
        assertThat(ItemInputTriggers.candidates(InputKind.DROP, sneaking = true))
            .containsExactly(SkillTrigger.SNEAK_DROP, SkillTrigger.DROP)
        assertThat(ItemInputTriggers.candidates(InputKind.SHOOT, sneaking = false))
            .containsExactly(SkillTrigger.SHOOT)
    }
}
