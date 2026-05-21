package com.astarworks.astera.core.application.config.mob

import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.entity.EntityCategory
import com.astarworks.astera.core.domain.entity.HologramKind
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.mob.LootTableId
import com.astarworks.astera.core.domain.mob.MobAiHint
import com.astarworks.astera.core.domain.mob.MobNameTagColor
import com.astarworks.astera.core.domain.mob.MobRegionId
import com.astarworks.astera.core.domain.mob.MobSkillBinding
import com.astarworks.astera.core.domain.skill.SkillId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MobYamlConfigTest {

    @Test
    fun `minimal config converts to MobSpec defaults`() {
        val config = MobYamlConfig(
            id = "astera:test-wolf",
            displayName = mapOf("en" to "Test Wolf"),
            attributes = mapOf("mob.max_hp" to 20.0),
        )
        val spec = config.toSpec()

        assertThat(spec.id.value).isEqualTo("astera:test-wolf")
        assertThat(spec.displayName.get(LocaleCode.EN)).isEqualTo("Test Wolf")
        assertThat(spec.baseEntityType).isEqualTo(EntityCategory.MOB)
        assertThat(spec.attributes.get(AttributeKey("mob.max_hp")))
            .isEqualTo(AttributeValue.NumberValue(20.0))
        assertThat(spec.lootTable).isEqualTo(LootTableId.EMPTY)
        assertThat(spec.ai).isEqualTo(MobAiHint.Default)
        assertThat(spec.equipment).isEmpty()
        assertThat(spec.skillBindings).isEmpty()
    }

    @Test
    fun `full config round-trips every field`() {
        val config = MobYamlConfig(
            id = "astera:forest-wolf",
            displayName = mapOf("en" to "Forest Wolf", "ja" to "森狼"),
            baseEntityType = "MOB",
            attributes = mapOf(
                "mob.max_hp" to 25.0,
                "mob.damage" to 4.0,
                "mob.speed" to 1.2,
            ),
            appearance = MobAppearanceYaml(
                displayKind = DisplayKindYaml(type = "item", itemId = "wolf-model"),
                customNameVisible = true,
                nameTagColor = NameTagColorYaml(r = 200, g = 200, b = 200),
                glowing = true,
            ),
            equipment = listOf(
                MobEquipmentYaml(slot = "mainhand", itemId = "wolf-tooth"),
            ),
            skillBindings = listOf(
                MobSkillBindingYaml(
                    type = "on_player_near",
                    skillId = "wolf-bite",
                    range = 5.0,
                    cooldownTicks = 40,
                ),
                MobSkillBindingYaml(
                    type = "on_hp_threshold",
                    skillId = "wolf-howl",
                    percent = 0.3f,
                ),
                MobSkillBindingYaml(type = "on_death", skillId = "death-rattle"),
                MobSkillBindingYaml(
                    type = "on_damaged",
                    skillId = "counter-bite",
                    triggerChance = 0.25f,
                ),
                MobSkillBindingYaml(
                    type = "periodic",
                    skillId = "regen",
                    intervalTicks = 100,
                ),
            ),
            lootTable = "astera:wolf-drops",
            spawnConditions = SpawnConditionsYaml(
                biomeKeys = listOf("minecraft:forest"),
                timeOfDayRange = listOf(0L, 14000L),
                regionId = "forest-edge",
                minLightLevel = 0,
                maxLightLevel = 15,
            ),
            ai = "pathfind",
        )
        val spec = config.toSpec()

        assertThat(spec.displayName.get(LocaleCode.JA)).isEqualTo("森狼")
        assertThat(spec.appearance.glowing).isTrue()
        assertThat(spec.appearance.displayKind).isEqualTo(HologramKind.Item(ItemId("wolf-model")))
        assertThat(spec.appearance.nameTagColor).isEqualTo(MobNameTagColor(200, 200, 200))
        assertThat(spec.equipment).containsExactly(EquipSlot.MAINHAND to ItemId("wolf-tooth"))
        assertThat(spec.skillBindings).hasSize(5)
        assertThat(spec.skillBindings[0])
            .isEqualTo(MobSkillBinding.OnPlayerNear(SkillId("wolf-bite"), range = 5.0, cooldownTicks = 40))
        assertThat(spec.skillBindings[1])
            .isEqualTo(MobSkillBinding.OnHpThreshold(SkillId("wolf-howl"), percent = 0.3f))
        assertThat(spec.skillBindings[2]).isEqualTo(MobSkillBinding.OnDeath(SkillId("death-rattle")))
        assertThat(spec.lootTable.value).isEqualTo("astera:wolf-drops")
        assertThat(spec.spawnConditions.biomeKeys).contains("minecraft:forest")
        assertThat(spec.spawnConditions.timeOfDayRange).isEqualTo(0L..14000L)
        assertThat(spec.spawnConditions.regionId).isEqualTo(MobRegionId("forest-edge"))
        assertThat(spec.ai).isEqualTo(MobAiHint.Pathfind)
    }

    @Test
    fun `unknown ai variant fails fast at conversion`() {
        val config = MobYamlConfig(id = "astera:test", ai = "boogie")
        assertThatThrownBy { config.toSpec() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ai 'boogie'")
    }

    @Test
    fun `unknown skill binding type fails fast`() {
        val config = MobYamlConfig(
            id = "astera:test",
            skillBindings = listOf(MobSkillBindingYaml(type = "on_lunch", skillId = "snack")),
        )
        assertThatThrownBy { config.toSpec() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("on_lunch")
    }

    @Test
    fun `unknown base entity type fails fast`() {
        val config = MobYamlConfig(id = "astera:test", baseEntityType = "DRAGON_KING")
        assertThatThrownBy { config.toSpec() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("DRAGON_KING")
    }

    @Test
    fun `display_kind text requires message_key`() {
        val config = MobYamlConfig(
            id = "astera:test",
            appearance = MobAppearanceYaml(
                displayKind = DisplayKindYaml(type = "text"),
            ),
        )
        assertThatThrownBy { config.toSpec() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("message_key")
    }

    @Test
    fun `time_of_day_range with wrong arity fails fast`() {
        val config = MobYamlConfig(
            id = "astera:test",
            spawnConditions = SpawnConditionsYaml(timeOfDayRange = listOf(0L)),
        )
        assertThatThrownBy { config.toSpec() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("2-element")
    }
}
