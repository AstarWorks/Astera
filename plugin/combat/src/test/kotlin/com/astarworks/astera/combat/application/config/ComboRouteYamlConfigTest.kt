package com.astarworks.astera.combat.application.config

import com.astarworks.astera.combat.domain.combo.ComboPattern
import com.astarworks.astera.combat.domain.combo.ComboRouteId
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.skill.SkillId
import com.charleskorn.kaml.Yaml
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ComboRouteYamlConfigTest {

    @Test
    fun `hit_count yaml round-trips through Kaml to domain`() {
        val src = """
            id: sword-finisher
            weapon_id: example-sword
            pattern:
              type: hit_count
              target: 5
            finisher: sword-uppercut
            bonus_damage_multiplier: 1.5
        """.trimIndent()
        val dto = Yaml.default.decodeFromString(ComboRouteYamlConfig.serializer(), src)
        val (id, route) = dto.toEntry()
        assertThat(id).isEqualTo(ComboRouteId("sword-finisher"))
        assertThat(route.weaponId).isEqualTo(ItemId("example-sword"))
        assertThat(route.finisher).isEqualTo(SkillId("sword-uppercut"))
        assertThat(route.bonusDamageMultiplier).isEqualTo(1.5)
        assertThat(route.pattern).isInstanceOf(ComboPattern.HitCount::class.java)
        assertThat((route.pattern as ComboPattern.HitCount).target).isEqualTo(5)
    }

    @Test
    fun `skill_sequence yaml decodes`() {
        val src = """
            id: spell-rotation
            weapon_id: example-staff
            pattern:
              type: skill_sequence
              skills:
                - blast
                - buff
            finisher: meteor
        """.trimIndent()
        val dto = Yaml.default.decodeFromString(ComboRouteYamlConfig.serializer(), src)
        val (_, route) = dto.toEntry()
        assertThat(route.pattern).isInstanceOf(ComboPattern.SkillSequence::class.java)
        assertThat((route.pattern as ComboPattern.SkillSequence).skills)
            .containsExactly(SkillId("blast"), SkillId("buff"))
    }

    @Test
    fun `timing_window yaml decodes`() {
        val src = """
            id: quick-strike
            weapon_id: example-dagger
            pattern:
              type: timing_window
              hits: 3
              window_ticks: 20
            finisher: flurry
        """.trimIndent()
        val dto = Yaml.default.decodeFromString(ComboRouteYamlConfig.serializer(), src)
        val (_, route) = dto.toEntry()
        assertThat(route.pattern).isInstanceOf(ComboPattern.TimingWindow::class.java)
        val tw = route.pattern as ComboPattern.TimingWindow
        assertThat(tw.hits).isEqualTo(3)
        assertThat(tw.windowTicks).isEqualTo(20)
    }

    @Test
    fun `unknown pattern type throws`() {
        val src = """
            id: bad
            weapon_id: example-sword
            pattern:
              type: telepathy
            finisher: x
        """.trimIndent()
        val dto = Yaml.default.decodeFromString(ComboRouteYamlConfig.serializer(), src)
        assertThatThrownBy { dto.toEntry() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("expected hit_count")
    }

    @Test
    fun `hit_count without target throws`() {
        val src = """
            id: no-target
            weapon_id: example-sword
            pattern:
              type: hit_count
            finisher: x
        """.trimIndent()
        val dto = Yaml.default.decodeFromString(ComboRouteYamlConfig.serializer(), src)
        assertThatThrownBy { dto.toEntry() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("requires target")
    }
}
