package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.ResourcePackStatusChanged
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.ResourcePackStatus
import com.astarworks.astera.core.domain.hud.status.StatusEffectHudGlyphs
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StatusEffectHudScenarioTest {
    @Test
    fun `burn and regen show icons with remaining seconds and stacks then expire without shifting HP`() {
        scenario("status-effect-icons-expire") {
            val target = player("target", team = "red")
            target.holds("pistol")
            sim.setMaxHealth(target, 200.0)
            val bus = sim.services.getOrThrow<IDomainEventBus>()
            bus.publish(ResourcePackStatusChanged(target.id, ResourcePackStatus.LOADED))
            advance(4)
            val before = (checkNotNull(sim.hud.statusLine(target.id)).component as HudComponent.FontText).widthPx
            val effects = sim.services.getOrThrow<IStatusEffectContainer>()
            val registry = sim.services.getOrThrow<IStatusEffectRegistry>()
            for ((id, stacks) in listOf("burn" to 2, "regen" to 1)) {
                effects.apply(StatusEffectInstance(
                    spec = checkNotNull(registry.find(StatusEffectId(id))),
                    targetPlayerId = target.id,
                    remainingTicks = 60,
                    currentStacks = stacks,
                    appliedAtTick = sim.tick,
                ))
            }
            advance(4)
            assertThat(sim.hud.statusEffects(target.id)).containsExactly(
                SimHudRecorder.StatusEffectReadout("burn", 3, 2),
                SimHudRecorder.StatusEffectReadout("regen", 3, 1),
            )
            assertThat(sim.hud.statusLineText(target.id)).contains("effect:burn{seconds=3,stacks=2}", "effect:regen{seconds=3,stacks=1}")
            val line = checkNotNull(sim.hud.statusLine(target.id))
            assertThat((line.component as HudComponent.FontText).widthPx).isEqualTo(before)
            assertThat(line.anchorLeftPx).isEqualTo(-91)
            advance(20)
            assertThat(sim.hud.statusEffects(target.id).map { it.seconds }).containsExactly(2, 2)
            advance(60)
            assertThat(sim.hud.statusEffects(target.id)).isEmpty()
            assertThat(sim.hud.statusLineText(target.id)).doesNotContain("effect:")
        }
    }

    @Test
    fun `every shipped status plus immunity and silence has an icon glyph and shows as an icon with the pack loaded`() {
        scenario("status-effect-icons-all") {
            val target = player("target", team = "red")
            target.holds("pistol")
            sim.setMaxHealth(target, 200.0)
            val registry = sim.services.getOrThrow<IStatusEffectRegistry>()
            val ids = (registry.all().map { it.id.value } + listOf("immunity", "silence")).distinct()
            assertThat(StatusEffectHudGlyphs.IDS).containsAll(ids)
            val bus = sim.services.getOrThrow<IDomainEventBus>()
            bus.publish(ResourcePackStatusChanged(target.id, ResourcePackStatus.LOADED))
            val effects = sim.services.getOrThrow<IStatusEffectContainer>()
            for (id in ids) {
                effects.apply(StatusEffectInstance(
                    // silence / immunity are mechanic built-ins, not content files: stand-in specs with the same ids.
                    spec = registry.find(StatusEffectId(id)) ?: StatusEffectSpec(
                        id = StatusEffectId(id),
                        displayNameKey = MessageKey("astera.status.$id.name"),
                        kind = StatusKind.STACK_BUFF,
                        magnitude = 0.0,
                        stackable = false,
                        maxStacks = 1,
                    ),
                    targetPlayerId = target.id,
                    remainingTicks = 100,
                    currentStacks = 1,
                    appliedAtTick = sim.tick,
                ))
            }
            advance(4)
            assertThat(sim.hud.statusEffects(target.id).map { it.id }).containsAll(ids)
        }
    }
}
