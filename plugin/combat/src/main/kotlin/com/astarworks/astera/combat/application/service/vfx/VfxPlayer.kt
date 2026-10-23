package com.astarworks.astera.combat.application.service.vfx

import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway.DisplayHandle
import com.astarworks.astera.combat.application.service.HeldItemHider
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IVfxRegistry
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayKind
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.effect.vfx.SpriteFrames
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.effect.vfx.VfxElementKind
import com.astarworks.astera.core.domain.effect.vfx.VfxElementPlan
import com.astarworks.astera.core.domain.effect.vfx.VfxFreeze
import com.astarworks.astera.core.domain.effect.vfx.VfxId
import com.astarworks.astera.core.domain.effect.vfx.VfxParticleEmission
import com.astarworks.astera.core.domain.effect.vfx.VfxPlan
import com.astarworks.astera.core.domain.effect.vfx.VfxQuad
import com.astarworks.astera.core.domain.effect.vfx.VfxTargetKey
import com.astarworks.astera.core.domain.effect.vfx.targetWeightAt
import com.astarworks.astera.core.domain.effect.vfx.VfxTimeline
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.world.WorldId
import org.slf4j.Logger

/** Why [VfxPlayer.play] did not start an effect. */
public sealed class VfxPlayError {
    public data class UnknownVfx(val id: VfxId) : VfxPlayError()
    public data class InvalidTargetTracking(val id: VfxId) : VfxPlayError()

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
    /** Entity identity for optional native attachment; location anchors leave this null. */
    public val anchorEntityId: PlayerId? = null,
    /** Bound live target feet; null result permanently stops this effect. */
    public val target: (() -> Vec3?)? = null,
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
    /** Flipbook frame counts for `sprite` elements (ADR-0239); unknown sprites play as one frame. */
    private val sprites: SpriteFrames = SpriteFrames.NONE,
    /** When set, an effect played while the anchor's `hide_held_item` is waiting is staged (see [HeldItemHider]). */
    private val heldItems: HeldItemHider? = null,
) {

    private class ElementState(val plan: VfxElementPlan) {
        var handle: DisplayHandle? = null
        var nextUpdate: Int = 0
        var nextParticle: Int = 0
        var attached: Boolean = false

        /** Entity position offset from the anchor (quads sit at their own point; `0` for ordinary displays). */
        var offset: Vec3 = plan.spawnOffset
        var targetOffset: Vec3 = Vec3.ZERO
        var authoredTransform: DisplayTransform = plan.initial
        var lineCorrection: Vec3? = null

        /** Quad updates move the entity themselves, so the per-tick follow teleport would fight them. */
        val selfMoving: Boolean = plan.updates.any { it.position != null }
    }

    private class Instance(
        val request: VfxRequest, val plan: VfxPlan, val startedAt: Long,
        val targetReference: Vec3?, val targetTracks: Map<String, List<VfxTargetKey>>,
        val targetLines: Set<String>,
    ) {
        val elements: List<ElementState> = plan.elements.map(::ElementState)
        var position: Vec3 = request.origin
        var age: Int = 0

        /** Spawned hidden (view range 0) so it replaces the anchor's held weapon in the tick it is revealed. */
        var staged: Boolean = false
    }

    private val instances: MutableList<Instance> = ArrayList()
    private var reserved: Int = 0
    private var ticker: IScheduler.TaskHandle? = null

    /** Number of display entities currently reserved by live effects. */
    public val liveDisplays: Int get() = reserved

    public fun play(request: VfxRequest): Result<Unit, VfxPlayError> {
        val def = registry.find(request.vfxId) ?: return Result.failure(VfxPlayError.UnknownVfx(request.vfxId))
        if ((request.target != null) != (def.targetReference != null) ||
            (request.target != null && ((request.follow ?: def.follow) || request.target.invoke() == null))) {
            return Result.failure(VfxPlayError.InvalidTargetTracking(request.vfxId))
        }
        val effective = request.follow?.let { def.copy(follow = it) } ?: def
        val frame = effective.orient.frame(request.origin, request.look, request.toTarget)
        val plan = VfxTimeline.plan(effective, frame, request.seed, request.scale, sprites, request.freeze)
        val replaced = if (plan.follow && plan.replaceGroup != null && request.anchorEntityId != null) {
            instances.filter { it.plan.follow && it.plan.replaceGroup == plan.replaceGroup &&
                it.request.anchorEntityId == request.anchorEntityId && it.request.world == request.world }
        } else emptyList()
        val remaining = reserved - replaced.sumOf { it.plan.displayCount }
        if (remaining + plan.displayCount > maxLiveDisplays) {
            log.debug("VFX {} dropped: {} after replacement + {} displays exceed cap {}", request.vfxId, remaining, plan.displayCount, maxLiveDisplays)
            return Result.failure(VfxPlayError.OverCapacity(remaining, plan.displayCount, maxLiveDisplays))
        }
        // Keep the current effect if the new plan cannot fit; only replace after admission.
        val instance = Instance(request, plan, scheduler.currentTick(),
            def.targetReference?.let { frame.rotation.rotate(it * request.scale) },
            def.elements.filter { it.targetTrack.isNotEmpty() }.associate { it.id to it.targetTrack },
            def.elements.filter { it.targetLine }.map { it.id }.toSet())
        if (plan.attachToAnchor && request.anchorEntityId != null) {
            val reusable = replaced.flatMap { it.elements }.filter { state ->
                state.attached && state.handle?.let {
                    displays.isAttached(it, request.anchorEntityId, request.world)
                } == true
            }.toMutableList()
            for (state in instance.elements) {
                val next = state.plan
                if (next.spawnTick != 0 || next.billboard != DisplayBillboard.FIXED) continue
                val previous = reusable.firstOrNull { old ->
                    old.plan.kind == next.kind && old.plan.billboard == next.billboard &&
                        old.plan.brightness == next.brightness && old.plan.glowArgb == next.glowArgb &&
                        old.plan.viewRange == next.viewRange
                } ?: continue
                state.handle = previous.handle
                state.attached = true
                previous.handle = null
                reusable.remove(previous)
            }
        }
        replaced.forEach(::finish)
        reserved += plan.displayCount
        // The client needs one tick to render a new display, the held-item hide needs none: spawn the displays
        // invisible now, reveal them (and send the hide) next tick, when the client has them ticked.
        instance.staged = request.anchorEntityId?.let { heldItems?.stage(it) } == true
        if (advance(instance)) {
            finish(instance)
        } else {
            instances += instance
            if (ticker == null) ticker = scheduler.runRepeating(ticks(1), ticks(1)) {
                // Equal-tick FIFO dispatch puts this after already queued dash steps.
                // Sample the anchor after movement, not halfway through the mechanic queue.
                scheduler.runLater(ticks(0)) { tickAll() }
            }
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
            // play() already processed age 0. A delayed cast can precede our shared ticker
            // in this same server tick; it must not immediately consume age 1 as well.
            if (instance.startedAt == scheduler.currentTick()) continue
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
        if (instance.staged && age == 1) reveal(instance)
        val targetCorrection = instance.request.target?.let { target ->
            val live = target() ?: return true
            live - (instance.position + (instance.targetReference ?: return true))
        }
        val anchor = instance.request.anchor
        if (plan.follow && anchor != null) {
            anchor()?.let { instance.position = it }
            for (state in instance.elements) {
                if (!state.selfMoving) state.handle?.let { displays.teleport(it, instance.position + state.offset, 0f, 0f, plan.followTeleportTicks ?: FOLLOW_TELEPORT_TICKS) }
            }
        }
        for (state in instance.elements) {
            val e = state.plan
            val track = instance.targetTracks[e.elementId]
            if (targetCorrection != null && track != null) {
                state.targetOffset = targetCorrection * track.targetWeightAt(age - e.spawnTick - VfxTimeline.ANIMATION_DELAY_TICKS)
            }
            if (e.elementId in instance.targetLines) state.lineCorrection = targetCorrection
            if (e.kind !is VfxElementKind.Particle && age == e.spawnTick) spawn(instance, state)
            val handle = state.handle
            if (handle != null) {
                while (state.nextUpdate < e.updates.size && e.updates[state.nextUpdate].tick <= age) {
                    val u = e.updates[state.nextUpdate++]
                    if (u.tick < age) continue
                    u.block?.let { displays.setBlock(handle, it) }
                    u.text?.let { displays.setText(handle, listOf(it)) }
                    u.style?.let { displays.setTextStyle(handle, it.backgroundArgb, it.textOpacity, it.textRgb) }
                    u.position?.let {
                        state.offset = it
                        displays.teleport(handle, instance.position + it + state.targetOffset, 0f, 0f, (if (plan.follow && !state.attached) plan.followTeleportTicks else null) ?: u.interpolationTicks.coerceIn(0, MAX_TELEPORT_TICKS))
                    }
                    state.authoredTransform = u.transform
                    if (state.lineCorrection == null) displays.update(handle, u.transform, u.interpolationTicks)
                }
            }
            if (handle != null && state.lineCorrection != null) {
                displays.update(handle, lineTransform(state.authoredTransform, state.lineCorrection!!), 0)
            }
            if (handle != null && track != null && targetCorrection != null) {
                displays.teleport(handle, instance.position + state.offset + state.targetOffset, 0f, 0f, 0)
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

    /** Makes the staged displays visible and sends the waiting hide in the same server tick. */
    private fun reveal(instance: Instance) {
        for (state in instance.elements) {
            state.handle?.let { displays.setViewRange(it, state.plan.viewRange ?: DEFAULT_VIEW_RANGE) }
        }
        instance.request.anchorEntityId?.let { heldItems?.reveal(it) }
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
            transform = state.lineCorrection?.let { lineTransform(e.initial, it) } ?: e.initial,
            lifetimeTicks = (e.despawnTick - e.spawnTick).toLong().coerceAtLeast(1L),
            billboard = e.billboard,
            brightness = e.brightness,
            glowArgb = e.glowArgb,
            viewRange = if (instance.staged && instance.age == 0) 0f else e.viewRange,
        )
        val lines = when (val k = e.kind) {
            is VfxElementKind.Text -> listOf(k.text)
            is VfxElementKind.Quad -> listOf(VfxQuad.text(k))
            else -> emptyList()
        }
        // A replacement retains compatible passengers so the client never waits for a
        // new entity's first render. Reset animated content as well as its transform.
        state.handle?.let { handle ->
            when (val kind = spec.kind) {
                is DisplayKind.Block -> displays.setBlock(handle, kind.blockKey)
                is DisplayKind.Text -> {
                    displays.setText(handle, lines)
                    displays.setTextStyle(handle, kind.backgroundArgb, kind.textOpacity, kind.textRgb)
                }
                is DisplayKind.Item -> Unit // Identical kind includes item and display mode.
            }
            displays.teleport(handle, instance.position + e.spawnOffset + state.targetOffset, 0f, 0f, 0)
            displays.update(handle, e.initial, 0)
            return
        }
        val anchorId = instance.request.anchorEntityId
        if (instance.plan.follow && instance.plan.attachToAnchor && anchorId != null && e.billboard == DisplayBillboard.FIXED) {
            state.handle = displays.spawnAttached(instance.request.world, spec, lines, anchorId, e.spawnOffset)
            state.attached = state.handle != null
            if (!state.attached) log.debug("VFX {}: native attachment unavailable for '{}'", instance.request.vfxId, e.elementId)
        }
        if (state.handle == null) state.handle = displays.spawn(instance.request.world, instance.position + e.spawnOffset + state.targetOffset, spec, lines)
        if (state.handle == null) log.debug("VFX {}: display for element '{}' was not spawned", instance.request.vfxId, e.elementId)
    }

    /** Block transforms use corner origins. Reconstruct both centreline endpoints before stretching. */
    private fun lineTransform(t: DisplayTransform, correction: Vec3): DisplayTransform {
        val center = t.translation + t.rotation.rotate(t.scale * 0.5)
        val half = t.rotation.rotate(Vec3(0.0, 0.0, t.scale.z * 0.5))
        val start = center - half
        val end = center + half + correction
        val delta = end - start
        val length = delta.length()
        val rotation = if (length < 1e-8) t.rotation else Quaternion.lookRotation(delta, t.rotation.rotate(Vec3(0.0, 1.0, 0.0)))
        val scale = Vec3(t.scale.x, t.scale.y, length)
        return DisplayTransform((start + end) * 0.5 - rotation.rotate(scale * 0.5), rotation, scale)
    }

    private fun displayKind(kind: VfxElementKind): DisplayKind? = when (kind) {
        is VfxElementKind.Block -> DisplayKind.Block(kind.blockKey)
        is VfxElementKind.Item -> DisplayKind.Item(kind.itemId, kind.displayMode, kind.itemModel)
        is VfxElementKind.Text -> DisplayKind.Text(
            lines = listOf(LocalizedText(mapOf(LocaleCode.EN to kind.text))),
            backgroundArgb = kind.backgroundArgb,
            shadow = kind.shadow,
            seeThrough = kind.seeThrough,
            lineWidth = kind.lineWidth,
            textOpacity = kind.textOpacity,
        )
        is VfxElementKind.Quad -> {
            val style = VfxQuad.style(kind, kind.colorRgb, kind.alpha)
            DisplayKind.Text(
                lines = listOf(LocalizedText(mapOf(LocaleCode.EN to VfxQuad.text(kind)))),
                backgroundArgb = style.backgroundArgb,
                shadow = false,
                seeThrough = kind.seeThrough,
                textOpacity = style.textOpacity,
                textRgb = style.textRgb,
            )
        }
        is VfxElementKind.Particle -> null
        is VfxElementKind.Sprite -> error("sprite elements are resolved to text by VfxTimeline.plan")
    }

    private fun finish(instance: Instance) {
        for (state in instance.elements) {
            state.handle?.let(displays::remove)
            state.handle = null
        }
        instances.remove(instance)
        if (instance.staged) {
            instance.staged = false
            instance.request.anchorEntityId?.let { heldItems?.rigEnded(it) }
        }
        reserved = (reserved - instance.plan.displayCount).coerceAtLeast(0)
    }

    public companion object {
        /** Server-wide cap on display entities reserved by running effects. */
        public const val MAX_LIVE_DISPLAYS: Int = 600

        /** `teleport_duration` for followed anchors: smooths the per-tick move. */
        public const val FOLLOW_TELEPORT_TICKS: Int = 2

        /** Client maximum for `teleport_duration`. */
        public const val MAX_TELEPORT_TICKS: Int = 59

        /** Vanilla `view_range` of a display that does not set one. */
        public const val DEFAULT_VIEW_RANGE: Float = 1.0f
    }
}
