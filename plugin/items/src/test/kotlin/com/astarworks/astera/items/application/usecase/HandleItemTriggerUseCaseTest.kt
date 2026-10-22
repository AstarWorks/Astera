package com.astarworks.astera.items.application.usecase

import com.astarworks.astera.core.domain.event.SkillCastRequested
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.SkillTrigger
import com.astarworks.astera.items.testfixtures.InMemoryItemRegistry
import com.astarworks.astera.items.testfixtures.itemSpec
import com.astarworks.astera.items.testfixtures.itemSpecWithBinding
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeResourcePoolService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class HandleItemTriggerUseCaseTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val origin = Vec3(0.0, 64.0, 0.0)

    private val pool = FakeResourcePoolService()
    private val eventBus = FakeDomainEventBus()
    private val items = InMemoryItemRegistry()

    private val uc = HandleItemTriggerUseCase(items, eventBus, pool)

    @Test
    fun `matching trigger publishes SkillCastRequested`() {
        val spec = itemSpecWithBinding(
            id = "example-sword",
            trigger = SkillTrigger.LEFT_CLICK,
            skillId = SkillId("fireball"),
        )
        items.register(spec)

        uc.execute(playerId, spec.id, SkillTrigger.LEFT_CLICK, origin)

        val published = eventBus.publishedOfType<SkillCastRequested>()
        assertThat(published).hasSize(1)
        assertThat(published[0].actorId).isEqualTo(playerId)
        assertThat(published[0].skillId).isEqualTo(SkillId("fireball"))
        assertThat(published[0].sourceItemId).isEqualTo(spec.id)
        assertThat(published[0].cooldownOverrideTicks).isNull()
    }

    @Test
    fun `non-matching trigger is a no-op`() {
        val spec = itemSpecWithBinding(
            id = "example-sword",
            trigger = SkillTrigger.LEFT_CLICK,
            skillId = SkillId("fireball"),
        )
        items.register(spec)

        uc.execute(playerId, spec.id, SkillTrigger.RIGHT_CLICK, origin)

        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `unknown item is a silent no-op`() {
        uc.execute(playerId, ItemId("does-not-exist"), SkillTrigger.LEFT_CLICK, origin)
        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `spec without bindings does not publish`() {
        val spec = itemSpec(id = "decorative", skillBindings = emptyList())
        items.register(spec)

        uc.execute(playerId, spec.id, SkillTrigger.LEFT_CLICK, origin)

        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `binding cooldown override is carried on the event and not gated here`() {
        val spec = itemSpecWithBinding(
            id = "burst-sword",
            trigger = SkillTrigger.LEFT_CLICK,
            skillId = SkillId("burst"),
            cooldownOverrideTicks = 40,
        )
        items.register(spec)

        // Combat owns the cooldown gate; Items forwards every trigger with the override.
        uc.execute(playerId, spec.id, SkillTrigger.LEFT_CLICK, origin)
        uc.execute(playerId, spec.id, SkillTrigger.LEFT_CLICK, origin)

        val published = eventBus.publishedOfType<SkillCastRequested>()
        assertThat(published).hasSize(2)
        assertThat(published.map { it.cooldownOverrideTicks }).containsOnly(40)
    }

    @Test
    fun `held item change updates the resource pool's active weapon`() {
        val id = ItemId("pistol")
        uc.onHeldItemChanged(playerId, id)
        assertThat(pool.calls).contains(FakeResourcePoolService.Call.SetActiveWeapon(playerId, id))
    }
}
