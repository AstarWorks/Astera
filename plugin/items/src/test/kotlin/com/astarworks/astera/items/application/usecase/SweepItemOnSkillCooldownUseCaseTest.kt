package com.astarworks.astera.items.application.usecase

import com.astarworks.astera.core.domain.event.SkillCooldownStarted
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSkillBinding
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.SkillTrigger
import com.astarworks.astera.items.application.port.outbound.IItemCooldownSweepGateway
import com.astarworks.astera.items.testfixtures.InMemoryItemRegistry
import com.astarworks.astera.items.testfixtures.itemSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class SweepItemOnSkillCooldownUseCaseTest {

    private val player = PlayerId(UUID.randomUUID())
    private val items = InMemoryItemRegistry()
    private val sweeps = mutableListOf<Triple<PlayerId, ItemId, Int>>()
    private val gateway = object : IItemCooldownSweepGateway {
        override fun sweep(playerId: PlayerId, itemId: ItemId, ticks: Int) {
            sweeps += Triple(playerId, itemId, ticks)
        }
    }
    private val uc = SweepItemOnSkillCooldownUseCase(items, gateway)

    private val pistol = itemSpec(
        id = "pistol",
        skillBindings = listOf(
            ItemSkillBinding(SkillTrigger.RIGHT_CLICK, SkillId("pistol-shot"), cooldownOverrideTicks = 6),
            ItemSkillBinding(SkillTrigger.SNEAK_RIGHT, SkillId("pistol-aim")),
            ItemSkillBinding(SkillTrigger.DROP, SkillId("pistol-reload")),
        ),
    )

    private fun started(trigger: SkillTrigger?, duration: Int, reload: Int? = null, item: ItemId? = pistol.id) =
        SkillCooldownStarted(player, SkillId("any"), item, trigger, duration, reload, atTick = 10)

    @Test
    fun `the primary binding's cooldown becomes the sweep, override already applied`() {
        items.register(pistol)

        uc.handle(started(SkillTrigger.RIGHT_CLICK, duration = 6))

        assertThat(sweeps).containsExactly(Triple(player, pistol.id, 6))
    }

    @Test
    fun `a secondary binding and casts without an item do not touch the sweep`() {
        items.register(pistol)

        uc.handle(started(SkillTrigger.SNEAK_RIGHT, duration = 80))
        uc.handle(started(SkillTrigger.RIGHT_CLICK, duration = 80, item = null))
        uc.handle(started(SkillTrigger.RIGHT_CLICK, duration = 80, item = ItemId("unknown-item")))

        assertThat(sweeps).isEmpty()
    }

    @Test
    fun `a reload shows as a sweep as long as the reload`() {
        items.register(pistol)

        uc.handle(started(SkillTrigger.DROP, duration = 30, reload = 45))

        assertThat(sweeps).containsExactly(Triple(player, pistol.id, 45))
    }
}
