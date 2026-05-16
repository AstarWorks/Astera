package com.astarworks.astera.domain.model.damage

import com.astarworks.astera.domain.model.geometry.Vec3
import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.domain.model.skill.SkillId
import com.astarworks.astera.domain.model.weapon.WeaponId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class DamageSourceTest {

    /**
     * Positive-control exhaustiveness: every [DamageSource] variant must be
     * reachable via the sealed `when`. If a new variant is added without
     * updating consumers, this won't compile.
     */
    @Test
    fun `sealed when covers every DamageSource variant`() {
        val player = PlayerId(UUID.randomUUID())
        val sources: List<DamageSource> = listOf(
            DamageSource.Environment,
            DamageSource.Weapon(WeaponId("example-sword"), player),
            DamageSource.Skill(SkillId("fireball"), player),
            DamageSource.Fall(distance = 4.0),
            DamageSource.Explosion(center = Vec3.ZERO, radius = 3.0),
        )

        val tags = sources.map { source ->
            when (source) {
                is DamageSource.Environment -> "env"
                is DamageSource.Weapon -> "weapon"
                is DamageSource.Skill -> "skill"
                is DamageSource.Fall -> "fall"
                is DamageSource.Explosion -> "explosion"
            }
        }

        assertThat(tags).containsExactly("env", "weapon", "skill", "fall", "explosion")
    }
}
