package com.astarworks.astera.match.domain.machine

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wave 5d [Machine] domain sanity tests (P2 minimal — domain types only).
 *
 * The two `data class` variants are exercised for construction + structural
 * equality; the two `sealed class` enumerations are exercised for
 * exhaustiveness so the compiler reports added variants in future waves.
 */
internal class MachineTest {

    private val pos = Vec3(10.5, 64.0, -32.5)

    @Test
    fun `SkillCoreMachine carries position skillId and trigger`() {
        val m = SkillCoreMachine(
            position = pos,
            skillId = "fireball",
            trigger = TriggerCondition.RedstoneActivated,
        )
        assertThat(m.position).isEqualTo(pos)
        assertThat(m.skillId).isEqualTo("fireball")
        assertThat(m.trigger).isSameAs(TriggerCondition.RedstoneActivated)
    }

    @Test
    fun `SkillCoreMachine is a Machine subtype`() {
        val m: Machine = SkillCoreMachine(
            position = pos,
            skillId = "wind-dash",
            trigger = TriggerCondition.RedstoneActivated,
        )
        assertThat(m.position).isEqualTo(pos)
    }

    @Test
    fun `SkillCoreMachine equality is structural`() {
        val a = SkillCoreMachine(pos, "fireball", TriggerCondition.RedstoneActivated)
        val b = SkillCoreMachine(pos, "fireball", TriggerCondition.RedstoneActivated)
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `TrapMachine carries position and effect`() {
        val m = TrapMachine(position = pos, effect = TrapEffect.Pitfall)
        assertThat(m.position).isEqualTo(pos)
        assertThat(m.effect).isSameAs(TrapEffect.Pitfall)
    }

    @Test
    fun `TrapMachine is a Machine subtype`() {
        val m: Machine = TrapMachine(position = pos, effect = TrapEffect.Pitfall)
        assertThat(m.position).isEqualTo(pos)
    }

    @Test
    fun `TrapMachine equality is structural`() {
        val a = TrapMachine(pos, TrapEffect.Pitfall)
        val b = TrapMachine(pos, TrapEffect.Pitfall)
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `TriggerCondition variants are singleton data objects`() {
        assertThat(TriggerCondition.RedstoneActivated).isSameAs(TriggerCondition.RedstoneActivated)
    }

    @Test
    fun `exhaustive when over TriggerCondition reaches every branch`() {
        val all: List<TriggerCondition> = listOf(TriggerCondition.RedstoneActivated)
        val labels = all.map { t ->
            when (t) {
                TriggerCondition.RedstoneActivated -> "redstone"
            }
        }
        assertThat(labels).containsExactly("redstone")
    }

    @Test
    fun `TrapEffect variants are singleton data objects`() {
        assertThat(TrapEffect.Pitfall).isSameAs(TrapEffect.Pitfall)
    }

    @Test
    fun `exhaustive when over TrapEffect reaches every branch`() {
        val all: List<TrapEffect> = listOf(TrapEffect.Pitfall)
        val labels = all.map { t ->
            when (t) {
                TrapEffect.Pitfall -> "pitfall"
            }
        }
        assertThat(labels).containsExactly("pitfall")
    }

    @Test
    fun `exhaustive when over Machine sealed hierarchy reaches every branch`() {
        // Pins the Wave 5d sealed shape — adding a new Machine subclass
        // forces every consumer (including this test) to update.
        val machines: List<Machine> = listOf(
            SkillCoreMachine(pos, "fireball", TriggerCondition.RedstoneActivated),
            TrapMachine(pos, TrapEffect.Pitfall),
        )
        val labels = machines.map { m ->
            when (m) {
                is SkillCoreMachine -> "skill"
                is TrapMachine -> "trap"
            }
        }
        assertThat(labels).containsExactly("skill", "trap")
    }
}
