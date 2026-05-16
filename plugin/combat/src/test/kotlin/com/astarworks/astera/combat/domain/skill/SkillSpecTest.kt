package com.astarworks.astera.combat.domain.skill

import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.weapons.domain.weapon.DamageProfile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SkillSpecTest {

    private val melee = SkillParams.MeleeParams(
        range = 3.0,
        knockback = 0.5,
        damage = DamageProfile(base = 5.0, attribute = DamageAttribute.PHYSICAL),
    )

    @Test
    fun `accepts non-negative cooldown`() {
        val spec = SkillSpec(
            id = SkillId("slash"),
            displayNameKey = MessageKey("astera.skill.slash.name"),
            descriptionKey = MessageKey("astera.skill.slash.desc"),
            archetype = SkillArchetype.MELEE,
            trigger = SkillTrigger.LEFT_CLICK,
            cooldownTicks = 0,
            params = melee,
        )
        assertThat(spec.cooldownTicks).isEqualTo(0)
    }

    @Test
    fun `rejects negative cooldown`() {
        assertThatThrownBy {
            SkillSpec(
                id = SkillId("slash"),
                displayNameKey = MessageKey("astera.skill.slash.name"),
                descriptionKey = MessageKey("astera.skill.slash.desc"),
                archetype = SkillArchetype.MELEE,
                trigger = SkillTrigger.LEFT_CLICK,
                cooldownTicks = -1,
                params = melee,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
