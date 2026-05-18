package com.astarworks.astera.stats.scenario

import com.astarworks.astera.combat.domain.armor.ArmorId
import com.astarworks.astera.combat.domain.armor.ArmorReduction
import com.astarworks.astera.combat.domain.armor.ArmorSlot
import com.astarworks.astera.combat.domain.armor.ArmorSpec
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wave _infra-1 canonical scenario #2.
 *
 * Verifies the [ArmorReduction] math at the value-object level: a piece with
 * 50% PHYSICAL reduction applies a 0.5 multiplier; a piece with 100%
 * (`flat(1.0)`) reduces incoming damage to zero.
 *
 * TODO(infra-2): once `IDamageGateway` + `IArmorEquipmentProvider` fakes exist
 * in `:plugin:test-fixtures`, replace the value-object check below with an
 * end-to-end `ApplyDamageUseCase` flow: target survives one hit at base
 * damage, dies to two hits (HP=2×base damage × (1-reduction)).
 */
class ArmorReducesDamageScenarioTest {

    @Test
    fun `armor reduces physical damage by stated reduction`() {
        scenario {
            val armorSpec = ArmorSpec(
                id = ArmorId("test-chest"),
                displayNameKey = MessageKey("astera.armor.test-chest"),
                slot = ArmorSlot.CHESTPLATE,
                reduction = ArmorReduction(default = 0.5),
            )
            harness.armorRegistry.register(armorSpec)

            val resolved = armorSpec.reduction.forAttribute(DamageAttribute.PHYSICAL)
            assertThat(resolved).isEqualTo(0.5)

            val fullBlock = ArmorReduction.flat(1.0).forAttribute(DamageAttribute.FIRE)
            assertThat(fullBlock).isEqualTo(1.0)
        }
    }
}
