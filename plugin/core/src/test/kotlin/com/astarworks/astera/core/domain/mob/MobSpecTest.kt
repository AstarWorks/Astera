package com.astarworks.astera.core.domain.mob

import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.entity.EntityCategory
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.skill.SkillId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MobSpecTest {

    private fun minimalSpec(
        id: MobTypeId = MobTypeId("astera:test-mob"),
        equipment: List<Pair<EquipSlot, ItemId>> = emptyList(),
        category: EntityCategory = EntityCategory.MOB,
    ): MobSpec = MobSpec(
        id = id,
        displayName = LocalizedText.EMPTY,
        baseEntityType = category,
        attributes = SimpleAttributeContainer(),
        equipment = equipment,
    )

    @Test
    fun `MobTypeId enforces namespaced kebab-case`() {
        assertThatThrownBy { MobTypeId("invalid id") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MobTypeId("no-namespace") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MobTypeId("UPPER:case") }
            .isInstanceOf(IllegalArgumentException::class.java)

        val id = MobTypeId("astera:forest-wolf")
        assertThat(id.namespace).isEqualTo("astera")
        assertThat(id.path).isEqualTo("forest-wolf")
        assertThat(id.toString()).isEqualTo("astera:forest-wolf")
    }

    @Test
    fun `MobSpec rejects duplicate equipment slots`() {
        assertThatThrownBy {
            minimalSpec(
                equipment = listOf(
                    EquipSlot.MAINHAND to ItemId("sword-a"),
                    EquipSlot.MAINHAND to ItemId("sword-b"),
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("duplicate equipment slot")
    }

    @Test
    fun `MobSpec rejects EXPERIENCE_ORB and ITEM as base type`() {
        assertThatThrownBy { minimalSpec(category = EntityCategory.EXPERIENCE_ORB) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { minimalSpec(category = EntityCategory.ITEM) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `MobSpec accepts MOB NPC DISPLAY PROJECTILE as base types`() {
        val categories = listOf(
            EntityCategory.MOB,
            EntityCategory.NPC,
            EntityCategory.DISPLAY,
            EntityCategory.PROJECTILE,
        )
        categories.forEach { cat ->
            val spec = minimalSpec(category = cat)
            assertThat(spec.baseEntityType).isEqualTo(cat)
        }
    }

    @Test
    fun `MobSkillBinding OnDamaged trigger chance must be in 0 to 1`() {
        assertThatThrownBy {
            MobSkillBinding.OnDamaged(SkillId("bite"), triggerChance = -0.1f)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            MobSkillBinding.OnDamaged(SkillId("bite"), triggerChance = 1.5f)
        }.isInstanceOf(IllegalArgumentException::class.java)

        // Boundary values accepted
        MobSkillBinding.OnDamaged(SkillId("bite"), triggerChance = 0f)
        MobSkillBinding.OnDamaged(SkillId("bite"), triggerChance = 1f)
    }

    @Test
    fun `MobSkillBinding OnPlayerNear validates range and cooldown`() {
        assertThatThrownBy {
            MobSkillBinding.OnPlayerNear(SkillId("bite"), range = 0.0, cooldownTicks = 10)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            MobSkillBinding.OnPlayerNear(SkillId("bite"), range = 5.0, cooldownTicks = -1)
        }.isInstanceOf(IllegalArgumentException::class.java)

        MobSkillBinding.OnPlayerNear(SkillId("bite"), range = 0.1, cooldownTicks = 0)
    }

    @Test
    fun `MobSkillBinding Periodic interval must be positive`() {
        assertThatThrownBy { MobSkillBinding.Periodic(SkillId("buff"), intervalTicks = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        MobSkillBinding.Periodic(SkillId("buff"), intervalTicks = 1)
    }

    @Test
    fun `MobAiHint enum covers Default Pathfind Stationary Flee Aggressive`() {
        assertThat(MobAiHint.entries).containsExactly(
            MobAiHint.Default,
            MobAiHint.Pathfind,
            MobAiHint.Stationary,
            MobAiHint.Flee,
            MobAiHint.Aggressive,
        )
    }

    @Test
    fun `MobBackendId well-known constants are stable`() {
        assertThat(MobBackendId.MINIMAL.value).isEqualTo("minimal")
        assertThat(MobBackendId.MYTHICMOBS.value).isEqualTo("mythicmobs")
    }

    @Test
    fun `MobBackendId rejects uppercase and whitespace`() {
        assertThatThrownBy { MobBackendId("MyBackend") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MobBackendId(" minimal") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { MobBackendId("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
