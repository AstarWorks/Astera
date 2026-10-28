package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.adapter.paper.skillengine.PaperSkillGameplayGateway
import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.domain.event.KnockbackApplied
import com.astarworks.astera.combat.domain.event.StatusApplied
import com.astarworks.astera.combat.domain.event.StatusExpired
import com.astarworks.astera.combat.domain.event.StatusRemoved
import com.astarworks.astera.core.application.port.outbound.IEgoGauge
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.event.EgoGaugeChanged
import com.astarworks.astera.core.domain.geometry.Vec3
import java.util.UUID

/**
 * The real combat pipeline behind [CastSim]'s gameplay port (SKL-18): the production [PaperSkillGameplayGateway] over the
 * `GameSim`'s real `ApplyDamageUseCase` / `ApplyStatusUseCase` / status container / knockback gateway. A skill's damage therefore
 * takes the same path as in the server (plain-hit 0.4 push by `KnockbackModifier`, `KnockbackApplied`, ego gain by
 * `EgoGaugeCombatListener`, status timers and expiry), and what that pipeline publishes is recorded in the gameplay-dump vocabulary
 * (`kb`, `ego`, `status`) the same way the v1 reference recorder reads it. [CastSim] keeps its own entity ids; [map] translates.
 */
class CombatLink(val sim: GameSim, val caster: SimEntity, val dummy: SimEntity, fillEgo: Boolean) {
    /** Where the caster stands (dashes move it); set by [CastSim]. */
    var feet: () -> Vec3 = { Vec3.ZERO }
    /** Receives the recorded lines; set by [CastSim]. */
    var sink: MutableList<String> = ArrayList()
    var origin: Vec3 = Vec3.ZERO
    /** Receives each skill impulse (real entity id) at the moment the gateway applies it; set by [CastSim]. */
    var onImpulse: (UUID, Vec3) -> Unit = { _, _ -> }

    private val startTick: Long

    init {
        // v1 reference order: ego filled first, one settling tick, then the cast starts at the tick read here.
        if (fillEgo) runCatching { val g = sim.services.getOrThrow<IEgoGauge>(); g.gain(caster.id, g.config.max) }
        sim.step(1)
        runCatching {
            sim.services.getOrThrow<IResourcePoolService>().setActiveWeapon(caster.id, caster.heldItem ?: com.astarworks.astera.core.domain.item.ItemId("sim"))
        }
        startTick = sim.tick
        val bus = sim.eventBus
        bus.subscribe(KnockbackApplied::class) { e ->
            sink += "{\"t\":${t()},\"e\":\"kb\",\"who\":\"${who(e.target.uuid)}\",\"strength\":${r(e.push.strength)},\"from\":{${rel(e.push.awayFrom)}},\"via\":\"combat\"}"
        }
        bus.subscribe(EgoGaugeChanged::class) { e -> sink += "{\"t\":${t()},\"e\":\"ego\",\"value\":${e.value}}" }
        bus.subscribe(StatusApplied::class) { e ->
            sink += "{\"t\":${t()},\"e\":\"status\",\"who\":\"${who(e.target.uuid)}\",\"id\":\"${e.effect.spec.id.value}\",\"op\":\"applied\",\"stacks\":${e.effect.currentStacks},\"dur\":${e.effect.remainingTicks}}"
        }
        bus.subscribe(StatusRemoved::class) { e ->
            sink += "{\"t\":${t()},\"e\":\"status\",\"who\":\"${who(e.target.uuid)}\",\"id\":\"${e.effectId.value}\",\"op\":\"removed\",\"stacks\":null,\"dur\":null}"
        }
        bus.subscribe(StatusExpired::class) { e ->
            sink += "{\"t\":${t()},\"e\":\"status\",\"who\":\"${who(e.target.uuid)}\",\"id\":\"${e.effectId.value}\",\"op\":\"expired\",\"stacks\":null,\"dur\":null}"
        }
    }

    private fun t(): Long = sim.tick - startTick
    private fun r(d: Double): String { val v = Math.round(d * 1e3) / 1e3; return (if (v == 0.0) 0.0 else v).toString() }
    private fun rel(v: Vec3) = "\"x\":${r(v.x - origin.x)},\"y\":${r(v.y - origin.y)},\"z\":${r(v.z - origin.z)}"
    private fun who(u: UUID): String = when (u) { caster.id.uuid -> "caster"; dummy.id.uuid -> "dummy"; else -> "other" }

    /** [CastSim]'s entity id -> the real sim entity id. */
    /** The real sim entity id -> [CastSim]'s entity id. */
    fun unmap(u: UUID): UUID = when (u) { caster.id.uuid -> SIM_CASTER; dummy.id.uuid -> simVictim(1); else -> u }

    fun map(u: UUID): UUID = when (u) { SIM_CASTER -> caster.id.uuid; simVictim(1) -> dummy.id.uuid; else -> u }

    val gateway = PaperSkillGameplayGateway(
        applyDamage = checkNotNull(sim.combat.applyDamage) { "combat wiring has no ApplyDamageUseCase" },
        applyStatus = checkNotNull(sim.combat.applyStatus) { "combat wiring has no ApplyStatusUseCase" },
        // The sim registers no physical knockback gateway (the wiring falls back to a no-op); the push itself is read off KnockbackApplied.
        // A skill impulse arrives here only once v1's hold clock released it, so [onImpulse] sees the tick it really lands on.
        knockback = object : IKnockbackGateway {
            private val inner = sim.services.get<IKnockbackGateway>()
            override fun applyImpulse(target: com.astarworks.astera.core.domain.player.PlayerId, impulse: Vec3) {
                inner?.applyImpulse(target, impulse)
                onImpulse(target.uuid, impulse)
            }
            override fun applyPush(target: com.astarworks.astera.core.domain.player.PlayerId, push: com.astarworks.astera.combat.domain.damage.KnockbackPush) {
                inner?.applyPush(target, push)
            }
        },
        casterPosition = { feet() },
        statusContainer = sim.services.getOrThrow<IStatusEffectContainer>(),
        entityEffects = sim.services.getOrThrow<IEntityEffectsGateway>(),
        attributes = sim.services.getOrThrow<IPlayerAttributeContainerStore>(),
        scheduler = sim.services.getOrThrow<IScheduler>(),
        randomizer = sim.services.getOrThrow<com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer>(),
        resourcePool = { sim.services.get<IResourcePoolService>() },
    )

    /** One real tick, in step with a [CastSim] tick. */
    fun advance() = sim.step(1)
}
