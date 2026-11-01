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
        // flame-burst rolls `rand() < 0.85` per hit. The roll is keyed by (cast seed, row seed, firing tick, victim id), so a
        // single pinned seed would break on any hit-timing change. Instead cast over 16 fixed seeds: every cast must hit, the
        // burn count must sit in the binomial band for p=0.85, n=16 (at least 9; P(X <= 8) = 0.00016, so a false failure of a
        // correct 85% roll is about 1 in 6000), and every seed whose burn applied must tick damage and then expire.
        val seeds = (0L until 16L).map { GameSim.DEFAULT_SEED + it }
        var burned = 0
        for (seed in seeds) {
            scenario("wand-applies-burn-$seed", seed = seed) {
                val caster = player("caster", team = "red")
                val target = mob("dummy", at = Vec3(0.0, groundY, 8.0), team = "blue")
                caster.holds("fire-staff")
                caster.faces(target)
                // Keep the dummy alive through repeated casts so the status effect can be observed.
                sim.setMaxHealth(target, 200.0)

                val container = sim.services.getOrThrow<IStatusEffectContainer>()
                val burn = StatusEffectId("burn")

                caster.cast("flame-burst")
                advance(85) // > the skill's 80-tick cooldown; the hit lands on the next tick
                assertThat(eventsOf<DamageDealt>().filter { it.target == target.id }).`as`("seed $seed hit").isNotEmpty()
                val applied = eventsOf<StatusApplied>().filter { it.target == target.id && it.effect.spec.id == burn }
                if (applied.isEmpty()) return@scenario
                burned++
                // One hit lands one StatusApplied (not two) and one burn stack, stamped with the sim tick.
                assertThat(applied).`as`("seed $seed").hasSize(1)
                assertThat(applied.single().effect.currentStacks).isEqualTo(1)
                assertThat(applied.single().atTick).isLessThanOrEqualTo(sim.tick)

                // Burn ticks through the health gateway (rerouted DoT) every 20 ticks, then expires.
                advance(120)
                val dotTicks = eventsOf<PlayerHealthChanged>().filter {
                    it.playerId == target.id && it.source.toString().contains("burn")
                }
                assertThat(dotTicks).`as`("seed $seed burn ticks").isNotEmpty()
                assertThat(eventsOf<StatusExpired>().any { it.target == target.id && it.effectId == burn }).isTrue()
                assertThat(container.has(target.id, burn)).isFalse()
            }
        }
        assertThat(burned).`as`("burn applied on $burned of ${seeds.size} casts (p=0.85)").isGreaterThanOrEqualTo(9)
    }

    @Test
    fun `a victim killed by the hit gets no status applied`() {
        scenario("wand-kill-skips-status") {
            val caster = player("caster", team = "red")
            val target = mob("dummy", at = Vec3(0.0, groundY, 8.0), team = "blue")
            caster.holds("fire-staff")
            caster.faces(target)
            sim.setMaxHealth(target, 1.0) // the 12-damage hit kills it before apply_status runs

            repeat(4) {
                caster.cast("flame-burst")
                advance(85)
            }

            assertThat(eventsOf<DamageDealt>().filter { it.target == target.id }).isNotEmpty()
            assertThat(eventsOf<StatusApplied>().filter { it.target == target.id }).isEmpty()
        }
    }
}
