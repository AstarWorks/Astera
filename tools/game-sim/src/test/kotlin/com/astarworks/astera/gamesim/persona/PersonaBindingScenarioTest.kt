package com.astarworks.astera.gamesim.persona

import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.core.adapter.api.event.IMcEvent
import com.astarworks.astera.core.application.port.outbound.IEgoGauge
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.skill.SkillTrigger
import com.astarworks.astera.gamesim.GameSim
import com.astarworks.astera.gamesim.scenario
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Real item input and content, without the preview command's force bypass. Visual fidelity
 * remains a separate, recorded client review; these checks cover bindings and display lifetime. */
class PersonaBindingScenarioTest {
    private val weapons = listOf(
        "bastion-greatsword", "couture-rapier", "crimson-chain-axe", "edict-blade",
        "ember-gauntlet", "gossamer-katana", "orrery-staff", "sigil-rifle",
        "vendetta-rifle", "verdict-hammer",
    )

    @TestFactory
    fun `every persona binding casts and cleans up`(): List<DynamicTest> {
        val registrySim = GameSim.boot("persona-binding-roster")
        val bindings = try {
            val registry = registrySim.services.getOrThrow<IItemRegistry>()
            weapons.flatMap { weapon ->
                checkNotNull(registry.find(ItemId(weapon))).skillBindings.map { weapon to it }
            }
        } finally {
            registrySim.shutdown()
        }
        assertThat(bindings).hasSize(51)
        return bindings.map { (weapon, binding) ->
            DynamicTest.dynamicTest("$weapon ${binding.trigger}: ${binding.skillId.value}") {
                scenario("binding-${binding.skillId.value}") {
                    val caster = player("caster", team = "red")
                    val target = mob("target", Vec3(0.0, groundY, 2.5), team = "blue")
                    sim.setMaxHealth(target, 1000.0)
                    caster.holds(weapon)
                    if (binding.trigger == SkillTrigger.SNEAK_DROP) {
                        caster.leftClick()
                        advance(150)
                        assertThat(ammo(caster)).isLessThan(12)
                    }
                    val castsBefore = eventsOf<SkillCast>().size
                    val gauge = sim.services.getOrThrow<IEgoGauge>()
                    gauge.gain(caster.id, gauge.config.max)
                    val input = when (binding.trigger) {
                        SkillTrigger.LEFT_CLICK, SkillTrigger.SNEAK_LEFT -> IMcEvent.InputKind.LEFT_CLICK
                        SkillTrigger.RIGHT_CLICK, SkillTrigger.SNEAK_RIGHT -> IMcEvent.InputKind.RIGHT_CLICK
                        SkillTrigger.DROP, SkillTrigger.SNEAK_DROP -> IMcEvent.InputKind.DROP
                        else -> error("Uncovered persona trigger: ${binding.trigger}")
                    }
                    val sneak = binding.trigger in setOf(
                        SkillTrigger.SNEAK_LEFT, SkillTrigger.SNEAK_RIGHT, SkillTrigger.SNEAK_DROP,
                    )
                    sim.input(caster, input, sneak)
                    advance(400)
                    assertThat(eventsOf<SkillCast>().drop(castsBefore).map { it.skillId }).containsExactly(binding.skillId)
                    if (binding.trigger == SkillTrigger.SNEAK_DROP) assertThat(ammo(caster)).isEqualTo(12)
                    assertThat(sim.displays.peakActive).isLessThanOrEqualTo(60)
                    assertThat(sim.displays.active).isEmpty()
                    if (binding.trigger != SkillTrigger.SNEAK_DROP) {
                        assertThat(sim.displays.spawned).isNotEmpty()
                    }
                }
            }
        }
    }
}
