package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.skill.SkillTrigger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

/**
 * Validates [ItemYamlConfig.toSpec] — the DTO-to-domain conversion used by
 * [com.astarworks.astera.core.application.contents.ItemContents.toEntry].
 *
 * Format-level invariants (material xor external_key, id parses) live in
 * [ItemYamlConfig.validate]; this suite focuses on the rest of the mapping.
 */
class ItemYamlConfigTest {

    @Test
    fun `vanilla config produces a Vanilla template with custom model data`() {
        val cfg = ItemYamlConfig(
            id = "example-sword",
            material = "IRON_SWORD",
            customModelData = 1001,
            name = mapOf("en" to "Sword", "ja" to "剣"),
            lore = mapOf("en" to listOf("a sword"), "ja" to listOf("剣だ")),
            rarity = "rare",
            levelRequirement = 5,
            attributes = AttributesYaml(attackDamage = 9.0, attackSpeed = 1.6),
            equipSlot = "mainhand",
            pdcTags = mapOf("astera:family" to "blade"),
        )

        val spec = cfg.toSpec()

        assertThat(spec.id.value).isEqualTo("example-sword")
        val tpl = spec.template as ItemTemplate.Vanilla
        assertThat(tpl.material).isEqualTo("IRON_SWORD")
        assertThat(tpl.customModelData).isEqualTo(1001)
        assertThat(spec.rarity).isEqualTo(Rarity.RARE)
        assertThat(spec.equipSlot).isEqualTo(EquipSlot.MAINHAND)
        assertThat(spec.levelRequirement).isEqualTo(5)
        assertThat(spec.attributes.attackDamage).isEqualTo(9.0)
        assertThat(spec.attributes.attackSpeed).isEqualTo(1.6)
        assertThat(spec.displayName.byLocale[LocaleCode.EN]).isEqualTo("Sword")
        assertThat(spec.displayName.byLocale[LocaleCode.JA]).isEqualTo("剣")
        assertThat(spec.lore.byLocale[LocaleCode.JA]).containsExactly("剣だ")
        assertThat(spec.pdcTags).containsEntry("astera:family", "blade")
    }

    @Test
    fun `external config produces an External template`() {
        val cfg = ItemYamlConfig(
            id = "my-blade",
            externalKey = "oraxen:my_blade",
        )

        val spec = cfg.toSpec()

        val tpl = spec.template as ItemTemplate.External
        assertThat(tpl.externalKey).isEqualTo("oraxen:my_blade")
        assertThat(spec.rarity).isEqualTo(Rarity.COMMON)
        assertThat(spec.equipSlot).isEqualTo(EquipSlot.MAINHAND)
    }

    @Test
    fun `validate rejects neither material nor external_key`() {
        val cfg = ItemYamlConfig(id = "bad")
        assertThatIllegalArgumentException().isThrownBy { cfg.toSpec() }
    }

    @Test
    fun `validate rejects both material and external_key`() {
        val cfg = ItemYamlConfig(
            id = "bad",
            material = "STONE",
            externalKey = "oraxen:thing",
        )
        assertThatIllegalArgumentException().isThrownBy { cfg.toSpec() }
    }

    @Test
    fun `unknown rarity is rejected with a clear message`() {
        val cfg = ItemYamlConfig(
            id = "bad",
            material = "STONE",
            rarity = "mythic",
        )
        assertThatIllegalArgumentException()
            .isThrownBy { cfg.toSpec() }
            .withMessageContaining("unknown rarity 'mythic'")
    }

    @Test
    fun `unknown equip slot is rejected`() {
        val cfg = ItemYamlConfig(
            id = "bad",
            material = "STONE",
            equipSlot = "necklace",
        )
        assertThatIllegalArgumentException()
            .isThrownBy { cfg.toSpec() }
            .withMessageContaining("unknown equip_slot 'necklace'")
    }

    @Test
    fun `skill binding YAML maps to domain ItemSkillBinding`() {
        val cfg = ItemYamlConfig(
            id = "skilled-sword",
            material = "IRON_SWORD",
            skills = listOf(
                ItemSkillBindingYaml(trigger = "right_click", skillId = "fireball", cooldownOverrideTicks = 40),
                ItemSkillBindingYaml(trigger = "LEFT_CLICK", skillId = "slash"),
            ),
        )

        val spec = cfg.toSpec()

        assertThat(spec.skillBindings).hasSize(2)
        val first = spec.skillBindings[0]
        assertThat(first.trigger).isEqualTo(SkillTrigger.RIGHT_CLICK)
        assertThat(first.skillId.value).isEqualTo("fireball")
        assertThat(first.cooldownOverrideTicks).isEqualTo(40)
        val second = spec.skillBindings[1]
        assertThat(second.trigger).isEqualTo(SkillTrigger.LEFT_CLICK)
        assertThat(second.skillId.value).isEqualTo("slash")
        assertThat(second.cooldownOverrideTicks).isNull()
    }

    @Test
    fun `unknown skill trigger is rejected`() {
        val cfg = ItemYamlConfig(
            id = "skilled-sword",
            material = "IRON_SWORD",
            skills = listOf(ItemSkillBindingYaml(trigger = "telepathic_command", skillId = "fireball")),
        )
        assertThatIllegalArgumentException()
            .isThrownBy { cfg.toSpec() }
            .withMessageContaining("unknown trigger 'telepathic_command'")
    }

    @Test
    fun `mutually exclusive drop policy bubbles up from domain init`() {
        val cfg = ItemYamlConfig(
            id = "bad",
            material = "STONE",
            dropOnDeath = true,
            keepOnDeath = true,
        )
        assertThatIllegalArgumentException().isThrownBy { cfg.toSpec() }
    }

    @Test
    fun `empty name and lore maps materialize as EMPTY localized values`() {
        val cfg = ItemYamlConfig(id = "minimal", material = "STONE")
        val spec = cfg.toSpec()
        assertThat(spec.displayName.byLocale).isEmpty()
        assertThat(spec.lore.byLocale).isEmpty()
    }
}
