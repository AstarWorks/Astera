package com.astarworks.astera.combat.domain.damage

import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.core.domain.barrier.BarrierId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.combat.domain.projectile.ProjectileId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class DamageSourceTest {

    /**
     * Positive-control exhaustiveness: every [DamageSource] variant must be
     * reachable via the sealed `when`. Updated for Wave _items-1 (ADR-0073):
     * `Weapon` was renamed to `Item` and `ProjectileOrigin.FromWeapon` to
     * `FromItem`.
     */
    @Test
    fun `sealed when covers every DamageSource variant`() {
        val player = PlayerId(UUID.randomUUID())
        val projectileId = ProjectileId(UUID.randomUUID())
        val sources: List<DamageSource> = listOf(
            DamageSource.Environment,
            DamageSource.Item(ItemId("example-sword"), player),
            DamageSource.Skill(SkillId("fireball"), player),
            DamageSource.Fall(distance = 4.0),
            DamageSource.Explosion(center = Vec3.ZERO, radius = 3.0),
            DamageSource.StatusEffectTick(StatusEffectId("burn"), originPlayerId = player),
            DamageSource.Projectile(
                projectileId = projectileId,
                ownerId = player,
                origin = ProjectileOrigin.FromItem(ItemId("example-pistol")),
            ),
            DamageSource.Barrier(BarrierId("test-barrier")),
        )

        val tags = sources.map { source ->
            when (source) {
                is DamageSource.Environment -> "env"
                is DamageSource.Item -> "item"
                is DamageSource.Skill -> "skill"
                is DamageSource.Fall -> "fall"
                is DamageSource.Explosion -> "explosion"
                is DamageSource.StatusEffectTick -> "status-tick"
                is DamageSource.Projectile -> "projectile"
                is DamageSource.Barrier -> "barrier"
            }
        }

        assertThat(tags).containsExactly(
            "env", "item", "skill", "fall", "explosion", "status-tick", "projectile", "barrier",
        )
    }

    @Test
    fun `attackerIdOrNull derives attacker from each source kind`() {
        val player = PlayerId(UUID.randomUUID())
        assertThat(DamageSource.Environment.attackerIdOrNull()).isNull()
        assertThat(DamageSource.Item(ItemId("sword"), player).attackerIdOrNull()).isEqualTo(player)
        assertThat(DamageSource.Skill(SkillId("fireball"), player).attackerIdOrNull()).isEqualTo(player)
        assertThat(DamageSource.Fall(distance = 4.0).attackerIdOrNull()).isNull()
        assertThat(DamageSource.Explosion(center = Vec3.ZERO, radius = 3.0).attackerIdOrNull()).isNull()
        assertThat(DamageSource.StatusEffectTick(StatusEffectId("burn"), player).attackerIdOrNull()).isEqualTo(player)
        assertThat(DamageSource.StatusEffectTick(StatusEffectId("burn"), null).attackerIdOrNull()).isNull()
        assertThat(
            DamageSource.Projectile(
                projectileId = ProjectileId(UUID.randomUUID()),
                ownerId = player,
                origin = ProjectileOrigin.FromSkill(SkillId("fireball")),
            ).attackerIdOrNull(),
        ).isEqualTo(player)
        assertThat(DamageSource.Barrier(BarrierId("test")).attackerIdOrNull()).isNull()
    }
}
