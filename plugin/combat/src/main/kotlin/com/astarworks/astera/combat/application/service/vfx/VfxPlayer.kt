package com.astarworks.astera.combat.application.service.vfx

import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway.DisplayHandle
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IVfxRegistry
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.effect.vfx.VfxElementKind
import com.astarworks.astera.core.domain.effect.vfx.VfxElementPlan
import com.astarworks.astera.core.domain.effect.vfx.VfxFreeze
import com.astarworks.astera.core.domain.effect.vfx.VfxId
import com.astarworks.astera.core.domain.effect.vfx.VfxParticleEmission
import com.astarworks.astera.core.domain.effect.vfx.VfxPlan
import com.astarworks.astera.core.domain.effect.vfx.VfxTimeline
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.world.WorldId
import org.slf4j.Logger

/** Why [VfxPlayer.play] did not start an effect. */
public sealed class VfxPlayError {
    public data class UnknownVfx(val id: VfxId) : VfxPlayError()

    /** Starting would exceed the server-wide live display cap. */
    public data class OverCapacity(val reserved: Int, val requested: Int, val cap: Int) : VfxPlayError()
}

/** One effect instance to start. */
public class VfxRequest(
    public val vfxId: VfxId,
    public val world: WorldId,
    /** Anchor position at start. */
    public val origin: Vec3,
    /** Caster look direction (for `orient: look / yaw`). */
    public val look: Vec3?,
    /** Caster -> anchor direction (for `orient: to_target`). */
    public val toTarget: Vec3?,
    public val scale: Double = 1.0,
    /** Overrides the definition's `follow`; `null` keeps it. */
    public val follow: Boolean? = null,
    /** Live anchor position for `follow`; `null` result = keep the last known one. */
    public val anchor: (() -> Vec3?)? = null,
    public val seed: Long = 0L,
    /** The caster, for particle `viewers: caster|others`. */
    public val caster: PlayerId? = null,
    /** Hit-stop windows (effect ticks) that freeze rigs with `respect_hit_stop`. */
    public val freeze: List<VfxFreeze> = emptyList(),
)

/** One particle element emission handed to the runtime (which plans it through `ParticleEmitter`). */
public class VfxParticleCall(
    public val world: WorldId,
    public val at: Vec3,
    public val particleId: ParticleId,
    public val look: Vec3?,
    public val tangent: Vec3?,
    public val center: Vec3?,
    public val toward: Vec3?,
    public val source: PlayerId?,
)

/**
 * Runs `content/vfx` choreographies (ADR-0220): plans a definition with [VfxTimeline], then
 * replays the plan tick by tick through [IDisplayEntityGateway] -- spawn at the anchor, send
 * the planned (client-interpolated) transform updates, emit particles, teleport for `follow`,
 * remove at the end. One shared repeating task drives every live instance.
 *
 * At most [maxLiveDisplays] display entities are reserved at once; further instances are
 * dropped (`OverCapacity`). [shutdown] removes everything.
 */
public class VfxPlayer(
    private val scheduler: IScheduler,
    private val displays: IDisplayEntityGateway,
    private val registry: IVfxRegistry,
    private val emitParticle: (VfxParticleCall) -> Unit,
    private val log: Logger,
    private val maxLiveDisplays: Int = MAX_LIVE_DISPLAYS,
) {

    private class ElementState(val plan: VfxElementPlan) {
        var handle: DisplayHandle? = null
        var nextUpdate: Int = 0
        var nextParticle: Int = 0
    }

    private class Instance(val request: VfxRequest, val plan: VfxPlan) {
        val elements: List<ElementState> = plan.elements.map(::ElementState)
        var position: Vec3 = request.origin
        var age: Int = 0
    }

    private val instances: MutableList<Instance> = ArrayList()
    private var reserved: Int = 0
    private var ticker: IScheduler.TaskHandle? = null

    /** Number of display entities currently reserved by live effects. */
    public val liveDisplays: Int get() = reserved

    public fun play(request: VfxRequest): Result<Unit, VfxPlayError> {
        val def = registry.find(request.vfxId) ?: return Result.failure(VfxPlayError.UnknownVfx(request.vfxId))
        val effective = request.follow?.let { def.copy(follow = it) } ?: def
        val frame = effective.orient.frame(request.origin, request.look, request.toTarget)
        val plan = VfxTimeline.plan(effective, frame, request.seed, request.scale, request.freeze)
        if (reserved + plan.displayCount > maxLiveDisplays) {
            log.debug("VFX {} dropped: {} + {} live displays exceed the cap {}", request.vfxId, reserved, plan.displayCount, maxLiveDisplays)
            return Result.failure(VfxPlayError.OverCapacity(reserved, plan.displayCount, maxLiveDisplays))
        }
        val instance = Instance(request, plan)
        reserved += plan.displayCount
        if (advance(instance)) {
            finish(instance)
        } else {
            instances += instance
            if (ticker == null) ticker = scheduler.runRepeating(ticks(1), ticks(1)) { tickAll() }
        }
        return Result.success(Unit)
    }

    /** Removes every live display and stops ticking. */
    public fun shutdown() {
        instances.toList().forEach(::finish)
        instances.clear()
        ticker?.cancel()
        ticker = null
    }

    private fun tickAll() {
        for (instance in instances.toList()) {
            instance.age++
            try {
                if (advance(instance)) finish(instance)
            } catch (ex: Exception) {
                log.warn("VFX {} failed at age {}; stopping it", instance.request.vfxId, instance.age, ex)
                finish(instance)
            }
        }
        if (instances.isEmpty()) {
            ticker?.cancel()
            ticker = null
        }
    }

    /** Processes tick [Instance.age]; returns `true` once the effect is over. */
    private fun advance(instance: Instance): Boolean {
        val age = instance.age
        val plan = instance.plan
        val anchor = instance.request.anchor
        if (plan.follow && anchor != null) {
            anchor()?.let { instance.position = it }
            for (state in instance.elements) {
                state.handle?.let { displays.teleport(it, instance.position, 0f, 0f, FOLLOW_TELEPORT_TICKS) }
            }
        }
        for (state in instance.elements) {
            val e = state.plan
            if (e.kind !is VfxElementKind.Particle && age == e.spawnTick) spawn(instance, state)
            val handle = state.handle
            if (handle != null) {
                while (state.nextUpdate < e.updates.size && e.updates[state.nextUpdate].tick <= age) {
                    val u = e.updates[state.nextUpdate++]
                    if (u.tick < age) continue
                    u.block?.let { displays.setBlock(handle, it) }
                    u.text?.let { displays.setText(handle, listOf(it)) }
                    u.backgroundArgb?.let { displays.setBackground(handle, it) }
                    displays.update(handle, u.transform, u.interpolationTicks)
                }
            }
            val kind = e.kind
            if (kind is VfxElementKind.Particle) {
                while (state.nextParticle < e.particles.size && e.particles[state.nextParticle].tick <= age) {
                    val p = e.particles[state.nextParticle++]
                    if (p.tick == age) emitParticleCalls(instance, kind, p)
                }
            }
            if (handle != null && age >= e.despawnTick) {
                displays.remove(handle)
                state.handle = null
            }
        }
        return age >= plan.durationTicks
    }

    private fun emitParticleCalls(instance: Instance, kind: VfxElementKind.Particle, p: VfxParticleEmission) {
        val origin = instance.position
        val points = p.pathOffsets.ifEmpty { listOf(p.offset) }
        for (offset in points) {
            emitParticle(
                VfxParticleCall(
                    world = instance.request.world,
                    at = origin + offset,
                    particleId = kind.particleId,
                    look = instance.request.look,
                    tangent = p.tangent,
                    center = p.center?.let { origin + it },
                    toward = p.trailTarget?.let { origin + it },
                    source = instance.request.caster,
                ),
            )
        }
    }

    private fun spawn(instance: Instance, state: ElementState) {
        val e = state.plan
        val spec = DisplaySpec(
            kind = displayKind(e.kind) ?: return,
            transform = e.initial,
            lifetimeTicks = (e.despawnTick - e.spawnTick).toLong().coerceAtLeast(1L),
            billboard = e.billboard,
            brightness = e.brightness,
            glowArgb = e.glowArgb,
            viewRange = e.viewRange,
        )
        val lines = (e.kind as? VfxElementKind.Text)?.let { listOf(it.text) } ?: emptyList()
        state.handle = displays.spawn(instance.request.world, instance.position, spec, lines)
        if (state.handle == null) log.debug("VFX {}: display for element '{}' was not spawned", instance.request.vfxId, e.elementId)
    }

    private fun displayKind(kind: VfxElementKind): DisplayKind? = when (kind) {
        is VfxElementKind.Block -> DisplayKind.Block(kind.blockKey)
        is VfxElementKind.Item -> DisplayKind.Item(kind.itemId, kind.displayMode)
        is VfxElementKind.Text -> DisplayKind.Text(
            lines = listOf(LocalizedText(mapOf(LocaleCode.EN to kind.text))),
            backgroundArgb = kind.backgroundArgb,
            shadow = kind.shadow,
            seeThrough = kind.seeThrough,
            lineWidth = kind.lineWidth,
        )
        is VfxElementKind.Particle -> null
    }

    private fun finish(instance: Instance) {
        for (state in instance.elements) {
            state.handle?.let(displays::remove)
            state.handle = null
        }
        instances.remove(instance)
        reserved = (reserved - instance.plan.displayCount).coerceAtLeast(0)
    }

    public companion object {
        /** Server-wide cap on display entities reserved by running effects. */
        public const val MAX_LIVE_DISPLAYS: Int = 600

        /** `teleport_duration` for followed anchors: smooths the per-tick move. */
        public const val FOLLOW_TELEPORT_TICKS: Int = 2
    }
}
