package com.astarworks.astera.core.domain.skill.phase

import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.NumericValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PhaseMechanicsTest {

    private val damageHit = Mechanic.Damage(
        targets = Targeter.Target,
        components = listOf(DamageComponent(NumericValue.Literal(5.0), DamageAttribute.FIRE)),
    )

    @Test
    fun `Projectile defaults all phases to empty lists`() {
        val p = PhaseMechanics.Projectile()
        assertThat(p.cast).isEmpty()
        assertThat(p.hitEntity).isEmpty()
        assertThat(p.hitBlock).isEmpty()
        assertThat(p.miss).isEmpty()
        assertThat(p.expire).isEmpty()
    }

    @Test
    fun `Projectile carries per-phase mechanic lists`() {
        val p = PhaseMechanics.Projectile(
            hitEntity = listOf(damageHit),
        )
        assertThat(p.hitEntity).containsExactly(damageHit)
        assertThat(p.cast).isEmpty()
    }

    @Test
    fun `Melee defaults`() {
        val m = PhaseMechanics.Melee()
        assertThat(m.cast).isEmpty()
        assertThat(m.hitEntity).isEmpty()
        assertThat(m.miss).isEmpty()
    }

    @Test
    fun `Aoe defaults`() {
        val a = PhaseMechanics.Aoe()
        assertThat(a.cast).isEmpty()
        assertThat(a.hitEntity).isEmpty()
        assertThat(a.expire).isEmpty()
    }

    @Test
    fun `Buff defaults`() {
        val b = PhaseMechanics.Buff()
        assertThat(b.cast).isEmpty()
        assertThat(b.expire).isEmpty()
        assertThat(b.removed).isEmpty()
    }

    @Test
    fun `Teleport defaults`() {
        val t = PhaseMechanics.Teleport()
        assertThat(t.cast).isEmpty()
        assertThat(t.preTeleport).isEmpty()
        assertThat(t.postTeleport).isEmpty()
    }

    @Test
    fun `Summon defaults`() {
        val s = PhaseMechanics.Summon()
        assertThat(s.cast).isEmpty()
        assertThat(s.spawn).isEmpty()
        assertThat(s.summonDeath).isEmpty()
    }

    @Test
    fun `phase enums have expected values`() {
        assertThat(ProjectilePhase.entries).containsExactly(
            ProjectilePhase.CAST,
            ProjectilePhase.HIT_ENTITY,
            ProjectilePhase.HIT_BLOCK,
            ProjectilePhase.MISS,
            ProjectilePhase.EXPIRE,
        )
        assertThat(MeleePhase.entries).containsExactly(
            MeleePhase.CAST, MeleePhase.HIT_ENTITY, MeleePhase.MISS,
        )
        assertThat(AoePhase.entries).containsExactly(
            AoePhase.CAST, AoePhase.HIT_ENTITY, AoePhase.EXPIRE,
        )
        assertThat(BuffPhase.entries).containsExactly(
            BuffPhase.CAST, BuffPhase.EXPIRE, BuffPhase.REMOVED,
        )
        assertThat(TeleportPhase.entries).containsExactly(
            TeleportPhase.CAST, TeleportPhase.PRE_TELEPORT, TeleportPhase.POST_TELEPORT,
        )
        assertThat(SummonPhase.entries).containsExactly(
            SummonPhase.CAST, SummonPhase.SPAWN, SummonPhase.SUMMON_DEATH,
        )
    }
}
