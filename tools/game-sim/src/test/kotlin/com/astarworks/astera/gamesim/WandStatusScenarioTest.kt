package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.StatusApplied
import com.astarworks.astera.combat.domain.event.StatusExpired
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.PlayerHealthChanged
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Scenario 2: a caster's fire skill lands and applies a status effect that ticks and expires. */
class WandStatusScenarioTest {

    @Test
    fun `flame burst applies burn which ticks damage and expires`() {
        scenario("wand-applies-burn") {
            val caster = player("caster", team = "red")
            val target = mob("dummy", at = Vec3(0.0, groundY, 8.0), team = "blue")
            caster.holds("example-wand")
            caster.faces(target)
            // Keep the dummy alive through repeated casts so the status effect can be observed.
            sim.setMaxHealth(target, 200.0)

            val container = sim.services.getOrThrow<IStatusEffectContainer>()
            val burn = StatusEffectId("burn")

            // flame-burst applies burn with 85% chance per hit (non-seeded RNG in the dispatcher):
            // cast until it lands. Eight casts miss with probability < 3e-7.
            fun burnApplied() = eventsOf<StatusApplied>().any { it.target == target.id && it.effect.spec.id == burn }
            var attempts = 0
            while (!burnApplied() && attempts < 8) {
                attempts++
                caster.cast("flame-burst")
                advance(85) // > the skill's 80-tick cooldown; the hit lands on the next tick
            }
            assertThat(burnApplied()).isTrue()
            assertThat(eventsOf<DamageDealt>().filter { it.target == target.id }).isNotEmpty()

            // Burn ticks through the health gateway (rerouted DoT) every 20 ticks, then expires.
            advance(120)
            val dotTicks = eventsOf<PlayerHealthChanged>().filter {
                it.playerId == target.id && it.source.toString().contains("burn")
            }
            assertThat(dotTicks).isNotEmpty()
            assertThat(eventsOf<StatusExpired>().any { it.target == target.id && it.effectId == burn }).isTrue()
            assertThat(container.has(target.id, burn)).isFalse()
        }
    }
}
