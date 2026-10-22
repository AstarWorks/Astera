package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IResourceRegenScheduler
import com.astarworks.astera.core.application.service.DefaultResourceRegenScheduler
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.ResourceRegenned
import com.astarworks.astera.combat.domain.event.CastRejectionReason
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.event.SkillCastRejected
import com.astarworks.astera.core.domain.event.ResourceReloaded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skill.SkillId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** WP2 (ADR-0194): ammo and the shared energy pool in the cast path, reload on Shift+Q (sneak_drop), regen, cooldown override. */
class ResourcePoolScenarioTest {

    @Test
    fun `quit stops resource regeneration and a rejoin resumes it`() {
        scenario("resource-regen-quit-rejoin") {
            val knight = player("knight")
            val regen = sim.services.getOrThrow<IResourceRegenScheduler>() as DefaultResourceRegenScheduler
            assertThat(regen.trackedPlayers()).contains(knight.id)
            sim.detachPlayer(knight.id)
            assertThat(regen.trackedPlayers()).doesNotContain(knight.id)
            val before = eventsOf<ResourceRegenned>().size
            advance(40)
            assertThat(eventsOf<ResourceRegenned>()).hasSize(before)
            sim.attachPlayer(knight.id, knight.name)
            assertThat(regen.trackedPlayers()).contains(knight.id)
            advance(40)
            assertThat(eventsOf<ResourceRegenned>().size).isGreaterThan(before)
        }
    }


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

            // Shift+Q reloads; shooting mid-reload is refused, the magazine refills after 30 ticks.
            shooter.drop(sneaking = true)
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
    fun `staff and sword drain the same energy pool, which refuses when short and regenerates`() {
        scenario("energy-shared-pool") {
            val mage = player("mage", team = "red")
            val dummy = mob("dummy", at = Vec3(0.0, groundY, 12.0), team = "blue")
            sim.setMaxHealth(dummy, 500.0)
            mage.holds("frost-staff")
            mage.faces(dummy)
            assertThat(energy(mage)).isEqualTo(100) // one shared cap, no per-item override

            mage.rightClick()                 // frost-shard: 8
            mage.rightClick(sneaking = true)  // frost-nova: 25
            advance(1)
            val afterStaff = energy(mage)
            assertThat(afterStaff).isBetween(66, 68)

            // Switching to a blade keeps the same pool; the sword dash (30) and a swing (15) come out of it.
            mage.holds("knight-sword")
            assertThat(energy(mage)).isBetween(afterStaff, afterStaff + 1)
            mage.cast("sword-dash")
            advance(1)
            val afterDash = energy(mage)
            assertThat(afterDash).isBetween(afterStaff - 31, afterStaff - 29)

            // lightning-bolt costs 30 and the pool has been drawn down by both weapons: drain it until refused.
            mage.holds("thunder-staff")
            mage.cast("lightning-bolt")
            mage.cast("flame-burst")
            assertThat(eventsOf<SkillCastRejected>().map { it.reason }).contains(CastRejectionReason.INSUFFICIENT_ENERGY)
            val dry = energy(mage)
            assertThat(dry).isLessThan(30)

            // 15/s: a second later the pool covers a bolt again.
            advance(40)
            assertThat(energy(mage)).isGreaterThan(dry)

            // Fully regenerates to the shared cap, never beyond.
            advance(400)
            assertThat(energy(mage)).isEqualTo(100)
        }
    }

    @Test
    fun `energy is mirrored on the food bar and vanilla hunger cannot move it`() {
        scenario("energy-food-bar") {
            val knight = player("knight", team = "red")
            knight.holds("knight-sword")
            advance(2)
            assertThat(foodLevel(knight)).isEqualTo(20)

            knight.leftClick()
            advance(1)
            // 100 -> ~85 energy is 17 of 20 food points
            assertThat(foodLevel(knight)).isBetween(16, 18)

            advance(100)
            assertThat(energy(knight)).isEqualTo(100)
            assertThat(foodLevel(knight)).isEqualTo(20)
        }
    }

    @Test
    fun `sword swings spend energy which regenerates`() {
        scenario("sword-energy") {
            val knight = player("knight", team = "red")
            knight.holds("knight-sword")
            assertThat(energy(knight)).isEqualTo(100)

            knight.leftClick()
            advance(1)
            assertThat(energy(knight)).isBetween(84, 86)

            advance(100)
            assertThat(energy(knight)).isEqualTo(100)
        }
    }
}
