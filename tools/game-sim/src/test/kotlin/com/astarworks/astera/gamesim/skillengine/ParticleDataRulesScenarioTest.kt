package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.ParticleData
import com.astarworks.astera.core.domain.skillengine.table.Action
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every typed particle data reaches the row (and so the `ParticleBurst` the gateway maps 1:1 to vanilla), and a particle whose
 * data does not fit its vanilla `dataType` is a named load error instead of a cast-time drop.
 */
class ParticleDataRulesScenarioTest : SkillEngineScenario() {

    private fun skill(op: String): String = listOf(
        "apiVersion: astera/v3", "kind: Skill", "id: particle-data", "item: couture-rapier",
        "anchors:", "  - {id: body, anchor: caster, mode: follow}",
        "machine:", "  start: s", "  states:", "    s:", "      for: 20", "      enter:", "        - $op",
    ).joinToString("\n") + "\n"

    private fun data(op: String): ParticleData? =
        when (val r = loadInline(skill(op))) {
            is Result.Success -> r.value.table.states.flatMap { it.rows }.map { it.action }.filterIsInstance<Action.Particle>().single().data
            is Result.Failure -> throw AssertionError("did not load: ${r.error}")
        }

    private fun failure(op: String): String =
        when (val r = loadInline(skill(op))) {
            is Result.Success -> throw AssertionError("expected a load error for: $op")
            is Result.Failure -> r.error.toString()
        }

    @Test
    fun everyExpressibleVariantReachesTheRow() {
        assertThat(data("{id: p, op: particle, particle: block, block: \"minecraft:stone\", anchor: body}")).isEqualTo(ParticleData.Block("minecraft:stone"))
        assertThat(data("{id: p, op: particle, particle: block_crumble, block: dirt, anchor: body}")).isEqualTo(ParticleData.Block("dirt"))
        assertThat(data("{id: p, op: particle, particle: item, item: diamond, anchor: body}")).isEqualTo(ParticleData.Item("diamond"))
        assertThat(data("{id: p, op: particle, particle: sculk_charge, roll: 1.5, anchor: body}")).isEqualTo(ParticleData.SculkCharge(1.5f))
        assertThat(data("{id: p, op: particle, particle: shriek, delay: 5, anchor: body}")).isEqualTo(ParticleData.Shriek(5))
        assertThat(data("{id: p, op: particle, particle: dragon_breath, power: 0.5, anchor: body}")).isEqualTo(ParticleData.Power(0.5f))
        assertThat(data("{id: p, op: particle, particle: dust, color: \"#102030\", size: 1.0, anchor: body}"))
            .isEqualTo(ParticleData.Dust(com.astarworks.astera.core.domain.effect.ParticleColor(0x10, 0x20, 0x30), 1.0f))
        assertThat(data("{id: p, op: particle, particle: crit, anchor: body}")).isNull()
    }

    @Test
    fun dataThatDoesNotFitTheParticleIsALoadError() {
        failure("{id: p, op: particle, particle: flame, block: stone, anchor: body}")
        failure("{id: p, op: particle, particle: flame, color: \"#ffffff\", size: 1.0, anchor: body}")
        failure("{id: p, op: particle, particle: dust, item: diamond, anchor: body}")
        failure("{id: p, op: particle, particle: sculk_charge, power: 1.0, anchor: body}")
    }

    @Test
    fun aParticleThatNeedsDataButGetsNoneIsALoadError() {
        failure("{id: p, op: particle, particle: block, anchor: body}")
        failure("{id: p, op: particle, particle: item, anchor: body}")
        failure("{id: p, op: particle, particle: shriek, anchor: body}")
        failure("{id: p, op: particle, particle: trail, anchor: body}")
        failure("{id: p, op: particle, particle: vibration, anchor: body}")
    }

    @Test
    fun badShriekDelayIsALoadError() {
        failure("{id: p, op: particle, particle: shriek, delay: 2.5, anchor: body}")
        failure("{id: p, op: particle, particle: shriek, delay: -1, anchor: body}")
    }

    @Test
    fun twoDataSlotsAreRejected() {
        failure("{id: p, op: particle, particle: sculk_charge, roll: 1.0, power: 1.0, anchor: body}")
    }
}
