package com.astarworks.astera.combat.domain.skill

import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SkillSpecTest {

    private val melee = SkillParams.MeleeParams(
        range = 3.0,
        knockback = 0.5,
        damage = DamageProfile.single(5.0, DamageAttribute.PHYSICAL),
    )

    private val name = LocalizedText(mapOf(LocaleCode.EN to "Slash"))
    private val description = LocalizedText(mapOf(LocaleCode.EN to "A basic slash."))

    private val emptyMeleePhases = PhaseMechanics.Melee()

    @Test
    fun `accepts non-negative cooldown`() {
        val spec = SkillSpec(
            id = SkillId("slash"),
            name = name,
            description = description,
            archetype = SkillArchetype.MELEE,
            cooldownTicks = 0,
            params = melee,
            phases = emptyMeleePhases,
        )
        assertThat(spec.cooldownTicks).isEqualTo(0)
    }

    @Test
    fun `rejects negative cooldown`() {
        assertThatThrownBy {
            SkillSpec(
                id = SkillId("slash"),
                name = name,
                description = description,
                archetype = SkillArchetype.MELEE,
                cooldownTicks = -1,
                params = melee,
                phases = emptyMeleePhases,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
