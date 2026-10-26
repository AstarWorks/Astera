package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * "Weapons suddenly stop working": status effects (silence), cooldowns and ammo outlive a match, so a player who
 * ended one silenced, on a long cooldown and with an empty magazine could not shoot in the next one.
 * Every participant must start a match with a clean weapon state.
 */
class StaleWeaponStateNextMatchScenarioTest {

    @Test
    fun `silence, cooldown and an empty magazine left by a previous match do not block weapons in the next one`() {
        scenario("stale-weapon-state-next-match") {
            val red = player("red-1", team = "red")
            val blue = player("blue-1", team = "blue")
            val first = startMatch("annihilation", mapOf(red to "red", blue to "blue"), stage = "annihilation-arena")
            red.holds("pistol")
            red.rightClick() // initialises the ammo pool for the pistol
            advance(10)

            val silence = StatusEffectId("silence")
            val effects = sim.services.getOrThrow<IStatusEffectContainer>()
            effects.apply(
                StatusEffectInstance(
                    spec = StatusEffectSpec(silence, MessageKey("astera.status.silence.name"), StatusKind.SILENCE, 0.0, false, 1, 100_000),
                    targetPlayerId = red.id,
                    remainingTicks = 100_000,
                    currentStacks = 1,
                    appliedAtTick = sim.tick,
                ),
            )
            val cooldownKey = CooldownKey.Skill("pistol-shot")
            val cooldowns = sim.services.getOrThrow<ICooldownTracker>()
            cooldowns.start(red.id, cooldownKey, 100_000L)
            val pools = sim.services.getOrThrow<IResourcePoolService>()
            val ammo = checkNotNull(pools.currentPool(red.id)).ammo
            pools.spendResource(red.id, ResourceType.Ammo, ammo)

            sim.services.getOrThrow<IMatchLifecycle>().end(first, EndReason.AdminEnded)
            advance(5)
            assertThat(effects.has(red.id, silence)).`as`("the stale state is really there before match 2").isTrue()

            startMatch("annihilation", mapOf(red to "red", blue to "blue"), stage = "annihilation-arena")
            assertThat(effects.has(red.id, silence)).`as`("silence cleared at match start").isFalse()
            assertThat(cooldowns.isCoolingDown(red.id, cooldownKey)).`as`("cooldown cleared").isFalse()
            assertThat(checkNotNull(pools.currentPool(red.id)).ammo).`as`("magazine refilled").isGreaterThan(0)

            red.moveTo(blue.position + Vec3(0.0, 0.0, 6.0))
            red.holds("pistol")
            red.faces(blue)
            repeat(10) {
                if (eventsOf<DamageDealt>().none { e -> e.attacker == red.id }) {
                    red.rightClick()
                    advance(10)
                }
            }
            assertThat(eventsOf<DamageDealt>().filter { it.attacker == red.id }).`as`("the pistol deals damage in match 2").isNotEmpty()
        }
    }
}
