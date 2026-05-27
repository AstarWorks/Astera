package com.astarworks.astera.core.application.service.block

import com.astarworks.astera.core.application.port.outbound.block.BlockInteractionAction
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.BlockInteractAction
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the [InMemoryBlockInteractionRouter] contract (Wave 5block-embed,
 * ADR-0120).
 */
internal class InMemoryBlockInteractionRouterTest {

    private val world = WorldId("world")
    private val pos = Vec3(10.0, 64.0, 20.0)

    @Test
    fun `bindRightClick returns a handle that lookup resolves`() {
        val router = InMemoryBlockInteractionRouter()
        val action = BlockInteractionAction.TriggerSkill(SkillId("fireball"))

        val handle = router.bindRightClick(world, pos, action)

        assertThat(router.lookup(world, pos, BlockInteractAction.RightClick)).isEqualTo(action)
        assertThat(router.lookup(world, pos, BlockInteractAction.LeftClick)).isNull()
        assertThat(router.size).isEqualTo(1)
        assertThat(handle).isNotNull
    }

    @Test
    fun `bindLeftClick is independent of bindRightClick`() {
        val router = InMemoryBlockInteractionRouter()
        val left = BlockInteractionAction.TriggerSkill(SkillId("wind-dash"))
        val right = BlockInteractionAction.TriggerSkill(SkillId("fireball"))

        router.bindLeftClick(world, pos, left)
        router.bindRightClick(world, pos, right)

        assertThat(router.lookup(world, pos, BlockInteractAction.LeftClick)).isEqualTo(left)
        assertThat(router.lookup(world, pos, BlockInteractAction.RightClick)).isEqualTo(right)
        assertThat(router.size).isEqualTo(2)
    }

    @Test
    fun `re-bind on the same slot replaces and invalidates the old handle`() {
        val router = InMemoryBlockInteractionRouter()
        val first = BlockInteractionAction.TriggerSkill(SkillId("a"))
        val second = BlockInteractionAction.TriggerSkill(SkillId("b"))

        val firstHandle = router.bindRightClick(world, pos, first)
        val secondHandle = router.bindRightClick(world, pos, second)

        assertThat(secondHandle).isNotEqualTo(firstHandle)
        assertThat(router.lookup(world, pos, BlockInteractAction.RightClick)).isEqualTo(second)
        // Old handle no longer owns anything.
        assertThat(router.unbind(firstHandle)).isFalse
        assertThat(router.lookup(world, pos, BlockInteractAction.RightClick)).isEqualTo(second)
    }

    @Test
    fun `unbind returns true on first call and false on second`() {
        val router = InMemoryBlockInteractionRouter()
        val handle = router.bindRightClick(world, pos, BlockInteractionAction.TriggerSkill(SkillId("x")))

        assertThat(router.unbind(handle)).isTrue
        assertThat(router.unbind(handle)).isFalse
        assertThat(router.lookup(world, pos, BlockInteractAction.RightClick)).isNull()
    }

    @Test
    fun `lookup returns null for physical contact and off-hand use`() {
        val router = InMemoryBlockInteractionRouter()
        router.bindRightClick(world, pos, BlockInteractionAction.TriggerSkill(SkillId("a")))
        router.bindLeftClick(world, pos, BlockInteractionAction.TriggerSkill(SkillId("b")))

        assertThat(router.lookup(world, pos, BlockInteractAction.PhysicalContact)).isNull()
        assertThat(router.lookup(world, pos, BlockInteractAction.OffHandUse)).isNull()
    }

    @Test
    fun `lookup is per-position`() {
        val router = InMemoryBlockInteractionRouter()
        val a = Vec3(0.0, 64.0, 0.0)
        val b = Vec3(1.0, 64.0, 0.0)
        val skillA = BlockInteractionAction.TriggerSkill(SkillId("a"))

        router.bindRightClick(world, a, skillA)

        assertThat(router.lookup(world, a, BlockInteractAction.RightClick)).isEqualTo(skillA)
        assertThat(router.lookup(world, b, BlockInteractAction.RightClick)).isNull()
    }

    @Test
    fun `lookup is per-world`() {
        val router = InMemoryBlockInteractionRouter()
        val w1 = WorldId("world")
        val w2 = WorldId("nether")
        val skill = BlockInteractionAction.TriggerSkill(SkillId("a"))

        router.bindRightClick(w1, pos, skill)

        assertThat(router.lookup(w1, pos, BlockInteractAction.RightClick)).isEqualTo(skill)
        assertThat(router.lookup(w2, pos, BlockInteractAction.RightClick)).isNull()
    }

    @Test
    fun `Custom intent key validates format`() {
        // good
        BlockInteractionAction.Custom("stage.beacon.activate")
        BlockInteractionAction.Custom("event.boss-spawn")

        org.assertj.core.api.Assertions.assertThatThrownBy {
            BlockInteractionAction.Custom("")
        }.isInstanceOf(IllegalArgumentException::class.java)
        org.assertj.core.api.Assertions.assertThatThrownBy {
            BlockInteractionAction.Custom("UPPER")
        }.isInstanceOf(IllegalArgumentException::class.java)
        org.assertj.core.api.Assertions.assertThatThrownBy {
            BlockInteractionAction.Custom("has spaces")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
