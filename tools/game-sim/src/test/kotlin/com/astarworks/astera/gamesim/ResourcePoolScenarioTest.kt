package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.domain.event.CastRejectionReason
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.event.SkillCastRejected
import com.astarworks.astera.core.domain.event.ResourceReloaded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skill.SkillId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** WP2: ammo / mana / stamina pools in the cast path, reload on the drop key, regen, cooldown override. */
class ResourcePoolScenarioTest {

    @Test
    fun `pistol empties its magazine, the cast is rejected, drop reloads, it fires again`() {
        scenario("pistol-ammo-reload") {
            val shooter = player("shooter", team = "red")
            val dummy = mob("dummy", at = Vec3(0.0, groundY, 12.0), team = "blue")
            sim.setMaxHealth(dummy, 500.0)
            shooter.holds("pistol")
            shooter.faces(dummy)

            fun shots() = eventsOf<SkillCast>().count { it.skillId == SkillId("pistol-shot") }
            fun rejections() = eventsOf<SkillCastRejected>().map { it.reason }

            assertThat(ammo(shooter)).isEqualTo(12) // item capacity, not the generic 30

            // 12 shots 6 ticks apart: only possible because the item's cooldown_override_ticks (6)
            // beats the skill's own cooldown_ticks (8).
            repeat(12) {
                shooter.rightClick()
                advance(6)
            }
            assertThat(shots()).isEqualTo(12)
            assertThat(ammo(shooter)).isEqualTo(0)

            // Empty magazine: the cast is refused with feedback and spends nothing.
            shooter.rightClick()
            advance(6)
            assertThat(shots()).isEqualTo(12)
            assertThat(rejections()).containsExactly(CastRejectionReason.INSUFFICIENT_AMMO)

            // Q reloads; shooting mid-reload is refused, the magazine refills after 30 ticks.
            shooter.drop()
            advance(15)
            shooter.rightClick()
            assertThat(rejections()).contains(CastRejectionReason.RELOADING)
            assertThat(ammo(shooter)).isEqualTo(0)
            advance(16)
            assertThat(ammo(shooter)).isEqualTo(12)
            assertThat(eventsOf<ResourceReloaded>()).hasSize(1)

            shooter.rightClick()
            advance(2)
            assertThat(shots()).isEqualTo(13)
            assertThat(ammo(shooter)).isEqualTo(11)
        }
    }

    @Test
    fun `staff mana drains on casts, rejects when short and regenerates`() {
        scenario("staff-mana-regen") {
            val mage = player("mage", team = "red")
            val dummy = mob("dummy", at = Vec3(0.0, groundY, 12.0), team = "blue")
            sim.setMaxHealth(dummy, 500.0)
            mage.holds("frost-staff")
            mage.faces(dummy)
            assertThat(mana(mage)).isEqualTo(60) // item capacity

            mage.rightClick()                 // frost-shard: item default 8
            mage.rightClick(sneaking = true)  // frost-nova: skill cost 25
            advance(1)
            val afterCasts = mana(mage)
            assertThat(afterCasts).isBetween(27, 28)

            // lightning-bolt costs 30: refused while the pool is short.
            mage.cast("lightning-bolt")
            assertThat(eventsOf<SkillCastRejected>().map { it.reason })
                .containsExactly(CastRejectionReason.INSUFFICIENT_MANA)
            assertThat(mana(mage)).isEqualTo(afterCasts)

            // 6 mana/s: ~20 ticks later the pool covers it again.
            advance(20)
            assertThat(mana(mage)).isGreaterThanOrEqualTo(30)
            mage.cast("lightning-bolt")
            assertThat(eventsOf<SkillCast>().count { it.skillId == SkillId("lightning-bolt") }).isEqualTo(1)
            assertThat(mana(mage)).isLessThan(10)

            // Fully regenerates to the staff's capacity, never beyond.
            advance(400)
            assertThat(mana(mage)).isEqualTo(60)
        }
    }

    @Test
    fun `sword swings spend stamina which regenerates`() {
        scenario("sword-stamina") {
            val knight = player("knight", team = "red")
            knight.holds("knight-sword")
            assertThat(stamina(knight)).isEqualTo(100)

            knight.leftClick()
            advance(1)
            assertThat(stamina(knight)).isBetween(85, 86)

            advance(100)
            assertThat(stamina(knight)).isEqualTo(100)
        }
    }
}
