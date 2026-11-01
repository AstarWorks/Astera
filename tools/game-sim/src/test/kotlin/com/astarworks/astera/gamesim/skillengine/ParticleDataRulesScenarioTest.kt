package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.ParticleColor
import com.astarworks.astera.core.domain.effect.ParticleData
import com.astarworks.astera.core.domain.effect.ParticleTarget
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

    private fun row(op: String): Action.Particle =
        when (val r = loadInline(skill(op))) {
            is Result.Success -> r.value.table.states.flatMap { it.rows }.map { it.action }.filterIsInstance<Action.Particle>().single()
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
            .isEqualTo(ParticleData.Dust(ParticleColor(0x10, 0x20, 0x30), 1.0f))
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

    @Test
    fun colourFamiliesPickTheirVariantByTheFieldsWritten() {
        val a = ParticleColor(0xff, 0x00, 0x80)
        val b = ParticleColor(0x00, 0x40, 0xff)
        assertThat(data("{id: p, op: particle, particle: dust_color_transition, color: \"#ff0080\", to_color: \"#0040ff\", size: 1.5, anchor: body}"))
            .isEqualTo(ParticleData.DustTransition(a, b, 1.5f))
        assertThat(data("{id: p, op: particle, particle: entity_effect, color: \"#ff0080\", anchor: body}")).isEqualTo(ParticleData.Tint(a))
        assertThat(data("{id: p, op: particle, particle: flash, color: \"#ff0080\", anchor: body}")).isEqualTo(ParticleData.Tint(a))
        assertThat(data("{id: p, op: particle, particle: tinted_leaves, color: \"#ff0080\", anchor: body}")).isEqualTo(ParticleData.Tint(a))
        assertThat(data("{id: p, op: particle, particle: effect, color: \"#ff0080\", power: 0.5, anchor: body}")).isEqualTo(ParticleData.Spell(a, 0.5f))
        assertThat(data("{id: p, op: particle, particle: instant_effect, color: \"#0040ff\", power: 2, anchor: body}")).isEqualTo(ParticleData.Spell(b, 2.0f))
    }

    @Test
    fun trailAndVibrationResolveTheirTargetPerRow() {
        val trail = row("{id: p, op: particle, particle: trail, color: \"#ff0080\", target: body, duration: 12, anchor: body}")
        assertThat(trail.data).isEqualTo(ParticleData.Trail(ParticleTarget.Toward, ParticleColor(0xff, 0x00, 0x80), 12))
        assertThat(trail.target).isNotNull()
        val vib = row("{id: p, op: particle, particle: vibration, target: {of: body, offset: {up: 2}}, arrival_ticks: 8, anchor: body}")
        assertThat(vib.data).isEqualTo(ParticleData.Vibration(ParticleTarget.Toward, 8))
        assertThat(vib.target).isNotNull()
        assertThat(row("{id: p, op: particle, particle: crit, anchor: body}").target).isNull()
    }

    @Test
    fun badColourAndTravelFieldsAreLoadErrors() {
        failure("{id: p, op: particle, particle: dust_color_transition, color: \"#ff0080\", size: 1.0, anchor: body}")
        failure("{id: p, op: particle, particle: dust_color_transition, to_color: \"#ff0080\", size: 1.0, anchor: body}")
        failure("{id: p, op: particle, particle: dust, color: \"#ff0080\", to_color: \"#0040ff\", size: 1.0, anchor: body}")
        failure("{id: p, op: particle, particle: entity_effect, color: \"#ff0080\", power: 1.0, size: 1.0, anchor: body}")
        failure("{id: p, op: particle, particle: crit, color: \"#ff0080\", anchor: body}")
        failure("{id: p, op: particle, particle: dust, color: \"#ff0080\", anchor: body}")
        failure("{id: p, op: particle, particle: effect, power: 1.0, anchor: body}")
        failure("{id: p, op: particle, particle: trail, color: \"#ff0080\", target: body, anchor: body}")
        failure("{id: p, op: particle, particle: trail, target: body, duration: 10, anchor: body}")
        failure("{id: p, op: particle, particle: trail, color: \"#ff0080\", target: body, duration: 0, anchor: body}")
        failure("{id: p, op: particle, particle: vibration, arrival_ticks: 5, anchor: body}")
        failure("{id: p, op: particle, particle: vibration, target: body, arrival_ticks: 2.5, anchor: body}")
        failure("{id: p, op: particle, particle: vibration, target: body, arrival_ticks: 5, duration: 5, anchor: body}")
        failure("{id: p, op: particle, particle: vibration, color: \"#ff0080\", target: body, arrival_ticks: 5, anchor: body}")
        failure("{id: p, op: particle, particle: flame, target: body, arrival_ticks: 5, anchor: body}")
        failure("{id: p, op: particle, particle: vibration, target: nowhere, arrival_ticks: 5, anchor: body}")
    }

    @Test
    fun blockAndItemKeysAreCheckedAgainstTheVanillaRegistry() {
        failure("{id: p, op: particle, particle: block, block: not_a_block, anchor: body}")
        failure("{id: p, op: particle, particle: block, block: \"other:stone\", anchor: body}")
        failure("{id: p, op: particle, particle: item, item: not_an_item, anchor: body}")
        failure("{id: p, op: particle, particle: item, item: water, anchor: body}")
        assertThat(data("{id: p, op: particle, particle: block, block: \"minecraft:oak_log\", anchor: body}")).isEqualTo(ParticleData.Block("minecraft:oak_log"))
        assertThat(data("{id: p, op: particle, particle: item, item: netherite_sword, anchor: body}")).isEqualTo(ParticleData.Item("netherite_sword"))
    }

    @Test
    fun aParticleWhoseDataAstraDoesNotMapIsALoadError() {
        failure("{id: p, op: particle, particle: geyser, anchor: body}")
        failure("{id: p, op: particle, particle: geyser_base, anchor: body}")
    }
}
