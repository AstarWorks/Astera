package com.astarworks.astera.combat.domain.aoe

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.aoe.EnvironmentDamageApplied
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class EnvironmentDamageSourceTest {

    private val victim = PlayerId(UUID.randomUUID())

    @Test
    fun `Environment Fall rejects negative distance`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            DamageSource.Environment.Fall(distance = -1.0)
        }
    }

    @Test
    fun `Environment Drown rejects negative air`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            DamageSource.Environment.Drown(remainingAir = -1)
        }
    }

    @Test
    fun `Environment Fire rejects negative ticks`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            DamageSource.Environment.Fire(ticksOnFire = -10)
        }
    }

    @Test
    fun `Environment singletons are distinct types`() {
        val sources: List<DamageSource.Environment> = listOf(
            DamageSource.Environment.Generic,
            DamageSource.Environment.Lightning,
            DamageSource.Environment.Void,
            DamageSource.Environment.Suffocation,
        )
        assertThat(sources.toSet()).hasSize(4)
    }

    @Test
    fun `EnvironmentDamageApplied is a DomainEvent with non-negative damage`() {
        val event = EnvironmentDamageApplied(
            target = victim,
            source = DamageSource.Environment.Fall(distance = 12.0),
            finalDamage = 8.0,
            atTick = 1L,
        )
        assertThat(event.target).isEqualTo(victim)
        assertThat(event.source).isInstanceOf(DamageSource.Environment.Fall::class.java)
        assertThat(event.finalDamage).isEqualTo(8.0)
        assertThat(event.version).isEqualTo(1)
    }

    @Test
    fun `EnvironmentDamageApplied rejects negative damage`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            EnvironmentDamageApplied(
                target = victim,
                source = DamageSource.Environment.Generic,
                finalDamage = -0.5,
                atTick = 0L,
            )
        }
    }
}
